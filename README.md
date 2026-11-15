# Lyricraft for Spotify

A Fabric mod that shows the lyrics of whatever you're listening to on Spotify while you play Minecraft. They're synced line by line, karaoke style.

You don't log in to anything. Put the jar in your mods folder, play a song on Spotify, and the lyrics show up.

## What it does

- Shows synced lyrics for the song you're playing on the Spotify desktop app or the web player
- 9 ways to show them: a normal line above the hotbar, giant text in the sky, words orbiting around you, holograms, and more
- A Now Playing card with the album cover, song, artist and a progress bar
- Optional "vibe" effects that light up your screen in the album's colours on every word
- Overlays like a vinyl player, cassette, VHS filter, lo-fi rain and confetti
- Fixes its own timing as it goes, and remembers if you nudge a song earlier or later
- Client-side only, so it works on any server

Press **N** in game to open the settings. If you have Mod Menu installed, it's also under the Config button there. You can drag the card and the lyrics around the screen right from that menu.

## Lyric styles

Switch with **K**.

- **Classic**: a scrolling lyric sheet above the hotbar. The current line fills in as it's sung.
- **Sky Writing**: huge lyrics in the sky that drift towards wherever you're looking. In caves, buildings, the Nether and the End it falls back to Classic so you can still read them.
- **Orbit**: the words circle around you and pop as they're sung.
- **Hologram**: a little sci-fi panel floating in front of you.
- **Echoes**: each new line appears where you're looking and stays there in the world. Walk around during a song and you leave a trail of lyrics behind you.
- **Cinema**: black movie bars with the lyrics as subtitles.
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

If the lyrics are a bit early or late, press **[** or **]** to move them by 0.1 seconds. Lyricraft remembers that for the song. After a few corrections it also learns your usual delay (Bluetooth headphones, for example) and applies it to new songs.

It also keeps checking its clock against Spotify's and slowly corrects itself, so the lyrics don't drift during long songs. You can turn that off with `"autoSync": false` if you ever need to.

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

The lyrics come from [LRCLIB](https://lrclib.net), a free, open lyrics database. The only thing sent is the song's title, artist, album and length.

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
