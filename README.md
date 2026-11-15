# 🎤 Lyricraft for Spotify

**Live karaoke lyrics for whatever you're playing on Spotify, right in your Minecraft HUD.**
No login. No API keys. Just drop it in your mods folder and press play.

---

## ✨ Features

- **9 display styles**: from a clean HUD line to giant lyrics in the sky, neon signs, words orbiting around you and holograms that stay where you looked.
- **Settings menu (`N`, or the Config button in [Mod Menu](https://modrinth.com/mod/modmenu))**: a side panel with live preview. Every option is one click, and you see the change instantly.
- **Vibe mode**: the screen takes on the song's colours (taken from the **album cover**). Edge glow, aurora, screen tint, equalizer and particles react to every word, and each one can be switched off on its own, with a strength slider.
- **9 overlays**: vinyl record player, cassette walkman, VHS tape, synthwave sunset, spectrum ring, lo-fi rain, starry night, oscilloscope and party confetti.
- **Self-improving sync**: locks onto Spotify's clock and learns your timing corrections, per song and overall.
- **Karaoke sweep**: the current line fills in Spotify-green as it's sung, pixel by pixel, with a glowing progress edge.
- **Scrolling lyric sheet**: lines glide upward and fade as the song moves on, with the previous and next lines shown dimmed.
- **Instrumental breaks**: bouncing ♪ ♪ ♪ notes, then the next line fades in just before it starts.
- **Now Playing card**: **5 looks** (Card, Compact, Pill, Cover, Minimal) × **7 themes** (Glass, Dark, Light, Spotify, Song colours, Neon, Minecraft tooltip), any size and background opacity.
- **Drag & drop layout**: in the menu, just **drag the card and the lyrics** wherever you want them (snaps to edges and centre, scroll to resize, right-click to reset). There is also a full-screen layout editor. It shows the real **album cover** in pixel-art style with a vinyl that slides out while the song plays. Also shows a scrolling title, artist, where it's playing from, lyric status, a live equalizer and a progress bar with times.
- **Pauses with you**: pause Spotify and the lyrics fade away. Press play and they come back.
- **Zero setup**: reads what's playing straight from your computer. Works with the **Spotify desktop app** and the **Spotify Web Player** in your browser.
- **Client-side only**: works on any server, singleplayer or multiplayer. The server never knows.
- **Hides itself** in menus (but not in chat) and fades out when the music stops.

## 🎨 9 Display Styles (press `K` to switch)

| Style | What it looks like |
|---|---|
| **Classic** | A scrolling lyric sheet above your hotbar with a green karaoke sweep. |
| **Sky Writing** | Giant glowing lyrics written across the sky like the sun or moon. They slowly drift to wherever you look, and finished lines float up and dissolve. In caves, tunnels and buildings (or the Nether and the End) it smoothly switches to Classic. |
| **Orbit** | The words spin in a ring around you, each one lighting up and popping as it's sung. Finished lines get flung outward. |
| **Hologram** | A sci-fi panel hovering in front of you with scanlines, corner brackets and the odd flicker. It trails your view when you turn. |
| **Echoes** | Every new line **materialises where you are looking and stays pinned in the world**, projected by a little light beam and linked to the previous line by a glowing trail. Lines never land on top of each other, the Now Playing card or the hotbar: if the spot is taken they pick the nearest free one. Walk around while a song plays and you leave a trail of lyrics behind you. |
| **Cinema** | Black letterbox bars slide in, the song name sits in the top bar and the lyrics appear as big movie subtitles. |
| **Pop** | Words appear one by one as they're sung, each with a bouncy pop. |
| **Neon** | The line is a neon sign. Letters buzz on in the song's colours as they're sung, flicker now and then and switch off letter by letter. |
| **Wave** | Letters ride a travelling wave, turn into a flowing rainbow once sung and bounce harder on every beat. |

## 🌈 Vibe Mode (press `V`: off → subtle → full)

Lyricraft can't hear your music, but it knows exactly when every line and word is sung, so those become the beat:

- **Song colours**: every song gets its own colour palette (the same song always gets the same colours).
- **Mood lighting** (full): the world is dimmed and washed in the song's colours, like club lighting.
- **Breathing vignette**: the screen edges glow and pulse with each word and flash on every new line.
- **Aurora**: rippling light ribbons across the top of the screen.
- **Equalizer** (full): bars either side of your hotbar that bounce harder for faster songs.
- **Music particles**: sparkles drift up around you, a coloured note pops above your head on sung words, and in full mode every new line sends a ring of light across the ground (plus sparks for high-energy songs). Particles exist only on your screen.

## 📀 Overlays (press `H` to switch)

| Overlay | What it looks like |
|---|---|
| **Vinyl** | A record player in the corner. The record spins at 33⅓ rpm with light catching the grooves and a label in the song's colours. The tonearm moves inward as the song plays and lifts off when you pause. Long titles scroll. |
| **VHS** | Like watching an old tape: ▶ PLAY / ❚❚ PAUSE, a blinking REC dot, a running tape counter, today's date, scanlines, film grain and a tracking-noise band drifting down the screen. |
| **Synthwave** | A striped retro sun sits on the horizon with neon pink grid lines rushing towards you. Faster songs scroll faster, and the sun pulses on the beat. |
| **Visualizer** | A ring of spectrum-style bars around your crosshair that bounces with every sung word, in the song's colours. |
| **Cassette** | A walkman tape in the corner. The tape winds from the left reel to the right one as the song plays, and the smaller reel spins faster, just like a real cassette. The label shows the title in the song's colours. |
| **Lo-fi Rain** | Cosy rainy-window vibes: rain streaks, droplets on the glass slowly sliding down, and soft blurry city lights behind. |
| **Starry Night** | A twinkling night sky with a faint coloured milky way. A shooting star flies across every time a new line starts. |
| **Oscilloscope** | A glowing waveform across the top of the screen that gets bigger with every sung word. |
| **Confetti** | Party-popper bursts of spinning confetti from the corners on every new line. Busier songs get more confetti. |

Overlays work with any style and Vibe mode, so you can mix them freely (try **Neon + Synthwave + Vibe full**).

## ⏱️ Self-Improving Sync

- **Clock lock**: Lyricraft keeps measuring how fast Spotify's position really moves and quietly speeds up or slows down its own clock to match, so lyrics don't drift during long songs.
- **Learns your corrections**: when you nudge a song with `[` / `]`, that timing is remembered for that song. Your default timing for new songs is re-learned from your recent corrections, so a constant delay (like Bluetooth headphones) gets fixed everywhere after a few nudges.
- Stored in `config/lyricraft/sync.json`. Turn off the clock lock with `"autoSync": false`.

## 🎮 Controls

| Key | Action |
|---|---|
| `J` | Toggle lyrics on/off (the Now Playing card stays) |
| `N` | Open the Lyricraft menu |
| `K` | Switch display style |
| `V` | Vibe mode: off / subtle / full |
| `H` | Switch overlay (9 to choose from) |
| `[` | Lyrics earlier (−100 ms), remembered for this song |
| `]` | Lyrics later (+100 ms), remembered for this song |
| *(unbound)* | Cycle position: above hotbar → top → left → right |

All keys can be changed in **Options → Controls → Key Binds → Lyricraft**.

## ⚙️ Config

Everything is in the **Lyricraft menu (`N`)**. If you like editing files, it's saved in `config/lyricraft.json`:

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `true` | Show lyrics |
| `style` | `CLASSIC` | `CLASSIC`, `SKY`, `ORBIT`, `HOLOGRAM`, `ECHOES`, `CINEMA`, `POP`, `NEON`, `WAVE` |
| `vibe` | `SUBTLE` | `OFF`, `SUBTLE`, `FULL` |
| `overlay` | `NONE` | `NONE`, `VINYL`, `CASSETTE`, `VHS`, `SYNTHWAVE`, `VISUALIZER`, `RAIN`, `STARS`, `OSCILLOSCOPE`, `CONFETTI` |
| `vibeParticles` | `true` | Music particles around you in Vibe mode |
| `autoSync` | `true` | Lock the lyrics clock to Spotify |
| `offsetMs` | `0` | Learned default timing for new songs (positive = later), updated automatically |
| `position` | `ABOVE_HOTBAR` | Classic/Pop/Neon/Wave placement: `ABOVE_HOTBAR`, `TOP_CENTER`, `LEFT_SIDE`, `RIGHT_SIDE`, `CUSTOM` (dragged) |
| `scale` | `1.0` | Lyrics size (0.5 – 3.0) |
| `showNowPlaying` | `true` | Show the Now Playing card |
| `showContextLines` | `true` | Show previous/next lines |
| `hideInMenus` | `true` | Hide while a menu is open |
| `accentColor` | `0xFF1ED760` | Karaoke colour (ARGB, as a number) |
| `cardStyle` | `CARD` | `CARD`, `COMPACT`, `PILL`, `COVER`, `MINIMAL` |
| `cardTheme` | `GLASS` | `GLASS`, `DARK`, `LIGHT`, `SPOTIFY`, `SONG`, `NEON`, `TOOLTIP` |
| `lyricsX` / `lyricsY` | `0.5` / `0.84` | Where dragged lyrics sit (used when `position` is `CUSTOM`) |
| `cardScale` / `cardOpacity` | `1.0` / `0.8` | Card size (0.5 – 1.5) and background (0 – 1) |
| `cardX` / `cardY` | `0` / `0` | Card position (0 – 1 across/down the screen). Easier: drag it in the menu |
| `cardCover` / `cardVinyl` / `cardProgress` / `cardDetails` | `true` | Parts of the card |
| `accentFromCover` | `false` | Use the album cover's colour for the karaoke fill |
| `coverColors` | `true` | Vibe lighting uses the album cover's colours |
| `hideWhenPaused` | `true` | Fade the lyrics out while the music is paused |
| `vibeStrength` | `0.7` | How strong the screen effects are (0.1 – 1.0) |
| `vibeEdgeGlow` / `vibeAurora` / `vibeTint` / `vibeEqualizer` | `true` | Switch each screen effect on or off |

## 🔍 How does it work without logging in?

Lyricraft never talks to your Spotify account. It asks **your operating system** what's playing, which is the same info your keyboard's media keys and the volume popup already use:

- **Windows 10/11**: Windows media controls (via a small, readable PowerShell script saved to `config/lyricraft/media-windows.ps1`, so you can see exactly what it does). Works with the Spotify app and browsers.
- **macOS**: asks the Spotify app directly (Spotify desktop app only).
- **Linux**: MPRIS via `playerctl` (install it with `sudo apt install playerctl` or your distro's equivalent).

The album cover comes from the same place (Windows saves it next to the script as `cover-0.img`/`cover-1.img`, which get overwritten each song).

It then looks up time-synced lyrics on **[LRCLIB](https://lrclib.net)**, a free, open lyrics database. Only the song title, artist, album and length are sent.

## ❓ FAQ

**Some songs say "No lyrics found."**
LRCLIB is community-made, so very new or niche songs may be missing. You can add them on lrclib.net!

**Lyrics are a bit early or late.**
Use `[` and `]` to nudge them. The correction is remembered for that song, and after a few songs Lyricraft learns your usual delay and applies it to new songs automatically.

**YouTube is also playing. Which one does it show?**
The Spotify app always wins. Otherwise it uses whichever browser tab is playing.

## Requirements

- Minecraft **26.3**
- Fabric Loader **0.20.1+**
- **Fabric API**

---

*Lyricraft is not affiliated with, endorsed by, or sponsored by Spotify AB. Spotify is a trademark of Spotify AB.
Lyrics are provided by LRCLIB and belong to their respective owners.*
