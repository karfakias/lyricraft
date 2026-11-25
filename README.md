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
- See who else uses Lyricraft: a green ♫ after their name, and the line they're hearing floating above their head

Press **N** in game to open the settings. If you have Mod Menu installed, it's also under the Config button there. You can drag the card and the lyrics around the screen right from that menu.

## Lyric styles

Switch with **K**.

- **Classic** (the default): a scrolling lyric sheet above the hotbar. The current line fills in as it's sung.
- **Echoes** (our favourite, give it a try): each new line appears where you're looking and stays there in the world. Walk around during a song and you leave a trail of lyrics behind you.
- **Edit** makes it feel like a video edit, in the world itself. Every line gets stamped onto whatever you're looking at (a wall, the ground, a tree, or the sky), and the words slam onto the surface one by one as they're sung, kicking up dust from that block. It's real 3D text, so trees, blocks and mobs in front of it hide it. The camera zoom-punches and shakes a little on the beat (you can turn that off), and the first chorus is the big drop with a shockwave across the surface. About 290 popular songs, picked from what people actually edit to on TikTok and Reels, hit harder and get their own particles: hearts, rain, sparks, soul flames, notes. The words are thick 3D block letters like in Minecraft lyric edits, and the world reacts to what's sung: "fire" sets the words alight, "rain" and "snow" bring weather, "storm" drops a lightning bolt on the word, "gold" throws gold out of it, and so on. Each song starts with a slide that names its look ("CINEMATIC", "VILLAIN ARC", "LOVE STORY"...), and if you stand still on the chorus the view cuts to cinematic angles around you (move and it cuts straight back). The list is in `src/main/resources/assets/lyricraft/edit_songs.txt` if you want to add songs.
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

## Playing with friends

Players who use Lyricraft get a green ♫ after their name, in the tab list and above their head. If they're listening to something, the line they're hearing right now floats above their head, filling in as it's sung, with the song name under it. Yours shows to them too, and you can see your own in third person (F5).

If a friend near you uses the Echoes style, you see their panels too, in the same spots in the world, with their name and their own colour. They only show for players close by (48 blocks) in the same world, at most 8 at a time, and they never cover your own panels or each other: they slide out of the way, or stay hidden if there's no room.

This works on any server without a server mod, through a small Lyricraft online service. It works with official and offline accounts. Your Minecraft login never leaves the game: official accounts are checked with the key Minecraft already uses to sign chat messages. Only your song, artist, current line and where your Echoes panels are placed are shared, and only with players who are on the same server as you (you both see each other in the tab list). Nobody can look you up from elsewhere, and nothing is stored. Other players never see your IP address.

Everything is in the **Online** tab: turn sharing off to only show the badge, stop sharing your Echoes, hide other players' lyrics or Echoes, or switch the online part off completely.

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
No. It only runs on your side. Other Lyricraft players can see your badge, though, unless you switch Online features off.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or newer
- Fabric API
- Mod Menu (optional)

---

Not affiliated with Spotify. Lyrics are provided by LRCLIB and belong to their owners.
