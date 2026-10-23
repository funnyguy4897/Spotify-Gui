# Spotify Glass (Fabric, Minecraft 1.21.11)

A client-side mod. When Spotify is playing on your Linux machine, a rounded liquid-glass "now playing" card appears in the corner of your HUD. The card shows the album art, title, artist and a progress bar. Synced lyrics play out in the world, in one of two places:

- **Horizon:** huge text sitting on the skyline. It moves with you like the sky does and rises up from below the horizon as each line starts. Sung words light up karaoke-style, and the previous line drifts up into the sky.
- **Tree:** text floats just above the canopy of the nearest tree, preferring trees you're looking at. When a line ends, it falls out of the tree like leaves.

Press **K** to switch between horizon, tree and off. When it's off, the lyrics move onto the card.

## Get the mod (built by GitHub, no Java needed on your PC)

1. On https://github.com, click **+** (top right), then **New repository**. Name it `spotify-glass` and click **Create repository**.
2. On the new repo page, click **uploading an existing file**.
3. Unzip `spotify-glass-repo.zip`. In the file manager, press **Ctrl+H** to show hidden files so the `.github` folder is visible. Select everything *inside* the `spotify-glass-mod` folder and drag it onto the upload page. Then click **Commit changes**.
4. Open the **Actions** tab. A "Build mod" run starts by itself and takes about 5 minutes. A green tick means it worked.
5. Click the run, scroll to **Artifacts**, and download **spotifyglass-mod**. Unzip it to get `spotifyglass-1.0.0.jar`.

If the run shows a red cross, click it, open the **Build** step, and copy the lines that say `error:`.

## Install it

1. Install playerctl so the mod can see Spotify: `sudo apt install playerctl`
2. Install Fabric Loader for 1.21.11 with the installer from https://fabricmc.net/use/installer/
3. Download **Fabric API** for 1.21.11 from Modrinth.
4. Put both jars (Fabric API and `spotifyglass-1.0.0.jar`) into `~/.minecraft/mods/`.
5. Launch the Fabric 1.21.11 profile and play something on Spotify.

## Controls

| Key | Action |
|---|---|
| Right Shift | Open the editor: drag the card and lyrics, change the glass with sliders |
| O | Show or hide the card |
| K | Lyrics: on screen, then horizon, then tree, then off (on the card) |
| (unbound) | Play/pause, next, previous. Bind them in Options → Controls → Key Binds → Spotify Glass |

## Commands

| Command | What it does |
|---|---|
| `/sg edit` | Open the editor |
| `/sg skip` or `/sg next` | Next song |
| `/sg back` or `/sg prev` | Previous song |
| `/sg pause`, `/sg play`, `/sg pp` | Pause, play, toggle |
| `/sg restart` | Start the song again |
| `/sg seek 90` | Jump to 1:30 |
| `/sg forward 10`, `/sg rewind 10` | Skip ahead or back by seconds |
| `/sg volume 60` | Spotify volume (0 to 100) |
| `/sg shuffle` | Toggle shuffle |
| `/sg repeat off` / `song` / `playlist` | Repeat mode |
| `/sg lyrics screen` / `horizon` / `tree` / `off` | Where lyrics appear |
| `/sg corner top_left` (or `top_right`, `bottom_left`, `bottom_right`) | Snap the card to a corner |
| `/sg scale 1.5`, `/sg radius 14`, `/sg tint #9AD0FF` | Card size, roundness, frost colour |
| `/sg offset -300` | Shift lyric timing in milliseconds |
| `/sg refetch`, `/sg status`, `/sg reload` | Lyrics again, what's detected, re-read config |

Everything else lives in `~/.minecraft/config/spotifyglass.json`:
- **Glass:** tint strength, rim brightness, colour fringe, and the blurred album backdrop on/off.
- **Horizon lyrics:** height above the horizon, size, and whether they show through mountains.
- **Tree lyrics:** text size and search radius.
- **Detection:** the next-line preview, and the player names to look for.

## Notes

- Lyrics come from LRCLIB (free, no account) and are cached in `~/.minecraft/spotifyglass/lyrics`. Songs LRCLIB doesn't have will show the song title instead.
- Liquid glass in Minecraft's HUD can't blur the game behind it. The card gets its glassy look another way:
  - the album cover, blurred and shown through the card
  - light pooling at the top and a specular rim
  - a faint red and blue fringe on the edges
  - a slow band of light drifting across
- This mod was written against the 1.21.11 Fabric docs but hasn't been compiled yet. If the GitHub build fails, copy the `error:` lines and they can be fixed quickly. The most likely spots are text drawing in `WorldLyrics.java` and texture upload in `ArtManager.java`.
