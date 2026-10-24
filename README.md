# AI Bots (Fabric, Minecraft 1.21.11)

Server-side fake players ("bots") that appear as real players: they use real usernames and real skins,
show in the tab list, have real inventories, and are controlled with `/bot`.

**Status: Stage 1 foundation.** Spawning, removing, mass spawning, kits, settings and the death rule are
implemented. Combat AI, survival AI, difficulty levels and faction behaviour are NOT built yet
(difficulty and faction are stored on each bot, ready for those stages).

> This source has not been compiled by its author yet. The first GitHub build is the first real test.
> If it fails, paste the red error from the Actions log back into the chat and it will be fixed.

## Build with GitHub (no installs needed)

1. Create a free GitHub account and a new empty repository (e.g. `aibots`).
2. Upload everything in this folder (drag the unzipped contents into the repo page, "Add file > Upload files"),
   including the hidden `.github` folder.
3. Open the **Actions** tab. The "Build mod" workflow runs automatically (or click "Run workflow").
4. When it turns green, open the run and download the **aibots-mod-jar** artifact. Unzip it to get `aibots-0.1.0.jar`.

## Install

Fabric Loader 0.18.2+ and **Fabric API 0.141.3+1.21.11** are required. Put `aibots-0.1.0.jar` and Fabric API
in `.minecraft/mods` (singleplayer) or the server `mods` folder. Server owners must install it server-side;
players join with a normal client.

## Commands (need operator / permission level 2)

| Command | What it does |
|---|---|
| `/bot spawn <name> [kit]` | Spawn a bot with that real username (gets that account's skin) |
| `/bot massspawn <count> [kit]` | Spawn many bots, scattered around you. `/bot massspawn cancel` stops it |
| `/bot remove <name>`, `/bot removeall`, `/bot list` | Manage bots |
| `/bot kit create <kit>` or `/bot createkit <kit>` | Save YOUR inventory as a kit |
| `/bot kit load <bot> <kit>` or `/bot loadkit <bot> <kit>` | Give a bot a kit |
| `/bot kit loadall <kit>`, `/bot kit list`, `/bot kit delete <kit>` | More kit tools |
| `/bot difficulty <bot> <1-10>` | Stored for later stages |
| `/bot faction join/leave/list` | Stored for later stages |
| `/bot settings` | Show all settings |
| `/bot settings botleaveondeath true/false` | true: a bot that dies leaves. false: it respawns |
| `/bot settings maxbots <n>` | Cap (default 3000) |
| `/bot settings masspawnrate <per second>` | Default 2 |
| `/bot settings masspawnradius <blocks>` | Default 24 |
| `/bot settings allowofflinebots true/false` | Spawn names that are not real accounts (default skin) |

## Files (in `config/aibots/`)

- `config.json` - settings. `bots.json` - per-bot difficulty/faction.
- `names.txt` - one real username per line. Mass spawn uses these first (real skins), then random names.
- `kits/<name>.json` - saved kits.

## Honest limits

- **3000 bots is a cap, not a promise.** Every bot is a full player entity (chunk loading, entity tracking,
  ticking). Expect a normal PC/server to struggle well before 3000. Start at 20-50 and raise slowly.
- **Real skins need Mojang lookups**, which are rate limited and currently happen on the server thread.
  That is why mass spawn is throttled (2/second). Fast mass spawning will lag and may get rate limited;
  names that fail fall back to a default skin when allowofflinebots is true.
- A random generated name that is not a real account has no real skin.
- Use only on worlds/servers you own or have permission to run bots on.

## Credits

The fake-player technique follows Carpet Mod (MIT): https://github.com/gnembon/fabric-carpet
