# Lyricraft for Spotify - Windows media reader
#
# Reads the song that is currently playing from Windows' own media controls
# (the same thing that powers the media popup and your keyboard's media keys).
# It only READS: title, artist, position, duration, play/pause state, the album cover
# (saved next to this script as cover-0.img / cover-1.img so the mod can show it) and
# whether your sound is going to Bluetooth headphones (they play audio a bit late, so the
# lyrics need to wait for them). It sends nothing anywhere and changes nothing.
# One JSON line per update on stdout.

param([int]$GamePid = 0)

$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, [Type]$type) {
    $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(3000)
    $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$mgr = Await ($mgrType::RequestAsync()) $mgrType

if ($null -eq $mgr) {
    [Console]::Out.WriteLine('{"error":"no-media-manager"}')
    exit 1
}

$browsers = 'chrome|msedge|firefox|opera|brave|vivaldi|arc'

$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]
$asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1 }
$coverKey = ''
$coverPath = ''
$coverSlot = 0
$coverTries = 0
$coverRecheck = $false
$coverChanged = [DateTime]::UtcNow

function Save-Cover($props) {
    if ($null -eq $props.Thumbnail) { return '' }
    $ws = Await ($props.Thumbnail.OpenReadAsync()) $streamType
    if ($null -eq $ws) { return '' }
    $net = $asStream.Invoke($null, @($ws))
    $script:coverSlot = 1 - $script:coverSlot
    $path = Join-Path $PSScriptRoot ("cover-" + $script:coverSlot + ".img")
    $file = [System.IO.File]::Create($path)
    try { $net.CopyTo($file) } finally { $file.Close(); $net.Close() }
    return $path
}

# Which device is the sound coming out of? Bluetooth headphones add ~150-250 ms of delay.
$audioReady = $false
try {
    Add-Type -ErrorAction Stop -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace LyricraftAudio {
  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
  }
  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    int Activate(ref Guid iid, int clsCtx, IntPtr p, out IntPtr ppv);
    int OpenPropertyStore(int access, out IntPtr store);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
  }
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator {}
  public static class DefaultOutput {
    public static string Id() {
      try {
        var e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice d;
        if (e.GetDefaultAudioEndpoint(0, 1, out d) != 0 || d == null) return "";
        string id; d.GetId(out id); return id ?? "";
      } catch { return ""; }
    }
  }
}
"@
    $audioReady = $true
} catch { }
$outId = $null
$outName = ''
$outBt = $false

function Update-Output {
    if (-not $script:audioReady) { return }
    $id = [LyricraftAudio.DefaultOutput]::Id()
    if ($id -eq $script:outId) { return }
    $script:outId = $id
    $script:outName = ''
    $script:outBt = $false
    if (-not $id) { return }
    # The endpoint's parent device tells how it's connected: BTHENUM / BTHHFENUM / BTHLE = Bluetooth
    $ep = Get-PnpDevice -Class AudioEndpoint -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -like ('*' + $id.ToUpper() + '*') } | Select-Object -First 1
    if ($null -eq $ep) { return }
    $script:outName = [string]$ep.FriendlyName
    $parent = (Get-PnpDeviceProperty -InstanceId $ep.InstanceId -KeyName 'DEVPKEY_Device_Parent' -ErrorAction SilentlyContinue).Data
    $script:outBt = ([string]$parent) -match '^(BTHENUM|BTHHFENUM|BTHLE)'
}

$loops = 0
while ($true) {
    # Quit as soon as Minecraft is gone (also after a crash), so nothing is left running.
    $loops++
    if ($GamePid -gt 0 -and ($loops % 5) -eq 0 -and $null -eq (Get-Process -Id $GamePid -ErrorAction SilentlyContinue)) { exit 0 }
    # Check the sound output every ~2 s (cheap unless it actually changed)
    if (($loops % 5) -eq 1) { Update-Output }

    $session = $null
    $sessions = @($mgr.GetSessions())

    # 1) The Spotify desktop app always wins.
    $session = $sessions | Where-Object { $_.SourceAppUserModelId -match 'spotify' } | Select-Object -First 1

    # 2) Otherwise a browser (Spotify Web Player) that is actually playing.
    if ($null -eq $session) {
        $session = $sessions | Where-Object {
            $_.SourceAppUserModelId -match $browsers -and $_.GetPlaybackInfo().PlaybackStatus -eq 4
        } | Select-Object -First 1
    }

    # 3) Otherwise whatever Windows considers the current session.
    if ($null -eq $session) { $session = $mgr.GetCurrentSession() }

    if ($null -eq $session) {
        [Console]::Out.WriteLine('{"none":true}')
    } else {
        $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
        $tl = $session.GetTimelineProperties()
        $info = $session.GetPlaybackInfo()
        $playing = ($info.PlaybackStatus -eq 4)

        # Spotify only reports a real position every few seconds, together with the exact time
        # it did so. We pass both on ("raw" + "upd"), so the mod can work out the position itself
        # at the exact moment it needs it, with no delay from this script. "pos" is kept as a fallback.
        $raw = $tl.Position.TotalMilliseconds
        $pos = $raw
        $updated = $tl.LastUpdatedTime
        $upd = 0
        if ($updated.Year -gt 2000) {
            $upd = $updated.ToUnixTimeMilliseconds()
            if ($playing) { $pos += ([DateTimeOffset]::Now - $updated).TotalMilliseconds }
        }
        $dur = ($tl.EndTime - $tl.StartTime).TotalMilliseconds

        # Album cover: read when the song changes, and once more 1.5 s later because Spotify
        # sometimes swaps the picture a moment after the title. Gives up after a few tries.
        $key = [string]$props.Artist + '|' + [string]$props.Title
        if ($key -ne $coverKey) {
            $coverKey = $key; $coverPath = ''; $coverTries = 0; $coverRecheck = $true
            $coverChanged = [DateTime]::UtcNow
        }
        $late = $coverRecheck -and ([DateTime]::UtcNow - $coverChanged).TotalMilliseconds -gt 1500
        if (($coverPath -eq '' -and $coverTries -lt 6) -or $late) {
            if ($late) { $coverRecheck = $false }
            $coverTries++
            $saved = Save-Cover $props
            if ($saved) { $coverPath = $saved }
        }

        $out = [ordered]@{
            app      = [string]$session.SourceAppUserModelId
            title    = [string]$props.Title
            artist   = [string]$props.Artist
            album    = [string]$props.AlbumTitle
            pos      = [long]$pos
            raw      = [long]$raw
            upd      = [long]$upd
            dur      = [long]$dur
            playing  = $playing
            cover    = $coverPath
            out      = $outName
            bt       = [bool]$outBt
        }
        [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
    }
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 400
}
