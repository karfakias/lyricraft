# Lyricraft for Spotify

A Fabric mod that shows the lyrics of whatever you're listening to on Spotify while you play Minecraft. They're synced line by line, karaoke style.

You don't log in to anything. Put the jar in your mods folder, play a song on Spotify, and the lyrics show up.

## What it does

- Shows synced lyrics for the song you're playing on the Spotify desktop app or the web player
- 7 ways to show them: a normal line above the hotbar, lyrics that stay floating in the world where you looked, giant text in the sky, words orbiting around you, and more
- A Now Playing card with the album cover, song, artist and a progress bar
- Optional "vibe" effects that light up your screen in the album's colours on every word
- Overlays like a vinyl player, cassette, VHS filter, lo-fi rain and confetti
- Fixes its own timing as it goes, and remembers if you nudge a song earlier or later
- Client-side only, so it works on any server

Press **N** in game to open the settings. If you have Mod Menu installed, it's also under the Config button there. You can drag the card and the lyrics around the screen right from that menu.

## Lyric styles

Switch with **K**.

- **Classic** (the default): a scrolling lyric sheet above the hotbar. The current line fills in as it's sung.
- **Echoes** (our favourite, give it a try): each new line appears where you're looking and stays there in the world. Walk around during a song and you leave a trail of lyrics behind you.
- **Sky Writing**: huge lyrics in the sky that drift towards wherever you're looking. In caves, buildings, the Nether and the End it falls back to Classic so you can still read them.
- **Orbit**: the words circle around you and pop as they're sung.
- **Pop**: the words appear one at a time.
- **Neon**: the line is a flickering neon sign.
- **Wave**: the letters ride a wave.

## Now Playing card

The card has 5 looks (Card, Compact, Pill, Cover, Minimal) and 7 themes (Glass, Dark, Light, Spotify, Song colours, Neon, and Minecraft's purple tooltip). You can change its size and how see-through it is, and drag it anywhere. It stays on screen even when you turn the lyrics off with J. Turn it off in the Card tab if you don't want it.

## Vibe mode

Press **V** to cycle between off, subtle and full.

Lyricraft can't actually hear the music, but it knows when each word is sung, so it uses that as the beat. You get a glow around the screen edges, an aurora across the top and some particles around you. Full mode also tints the whole screen and adds equalizer bars next to the hotbar. Every effect can be turned off on its own in the menu, and there's a strength slider if it's too much.

## Overlays

Press **H** to switch overlays. They're extra decoration that works alongside any style:

Vinyl, Cassette, VHS, Synthwave, Visualizer, Lo-fi Rain, Starry Night, Oscilloscope, Confetti.

## Timing

A few things keep the lyrics in time without you doing anything:

- **Better lyrics timing.** Most songs on LRCLIB have several synced versions made by different people, and each one is a little off in its own way. Lyricraft downloads all the versions that match your song's length and uses the middle time of every line, so one person's mistakes don't count. Versions timed to a different cut of the song (radio edit, live) get ignored.
- **Exact position.** On Windows, Spotify only reports where it is every few seconds, along with the exact time it did. Lyricraft works the position out from that itself, so there's no delay in between.
- **Bluetooth.** If your sound goes to Bluetooth headphones, the lyrics wait a little for them, because Bluetooth plays audio late. Speakers and headphones each remember their own timing.

If a song is still early or late, press **[** or **]** to move it by 0.1 seconds. Lyricraft remembers that for the song and learns your usual delay from it. In the Sync tab you can also share your fix with LRCLIB, so the song is right for everyone. It always asks first.

There's also an optional extra lyrics source, NetEase Cloud Music, in the Sync tab. It's off by default because it's a Chinese service, and turning it on sends them the song title and artist.

## Controls

| Key | What it does |
|---|---|
| N | Open the settings |
| J | Lyrics on/off |
| K | Next lyric style |
| V | Vibe off / subtle / full |
| H | Next overlay |
| [ / ] | Lyrics earlier / later |

You can rebind all of these in Options → Controls → Key Binds → Lyricraft.

## How it works without a login

Lyricraft never touches your Spotify account. It asks your computer what's playing, which is the same info your media keys use:

- **Windows 10/11**: the Windows media controls. This goes through a small PowerShell script that gets saved to `config/lyricraft/media-windows.ps1`, so you can read exactly what it does. It works with the Spotify app and with Spotify in a browser.
- **macOS**: asks the Spotify app directly (desktop app only).
- **Linux**: uses `playerctl`, so install that first.

The lyrics come from [LRCLIB](https://lrclib.net), a free, open lyrics database. The only thing sent is the song's title, artist, album and length. The Windows script also checks whether your default sound output is Bluetooth, which stays on your PC.

## Config

Everything is in the settings menu, but it's all saved to `config/lyricraft.json` if you'd rather edit the file. Your per-song timing is saved in `config/lyricraft/sync.json`.

## FAQ

**It says "No lyrics found".**
LRCLIB doesn't have every song yet, especially new or less popular ones. You can add missing songs on their site.

**Something else is playing too, like YouTube. Which one does it show?**
The Spotify app always wins. If that isn't open, it uses whichever browser tab is playing.

**Will a server know I'm using it?**
No. It only runs on your side.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- Fabric API
- Mod Menu (optional)

---

Not affiliated with Spotify. Lyrics are provided by LRCLIB and belong to their owners.
