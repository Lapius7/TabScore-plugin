<div align="center">

<p align="center"><b>English</b> | <a href="README.ja.md">日本語</a></p>

<h1 align="center">TabScore</h1>

<h3 align="center">Show any player stat as the number next to names in the tab list.</h3>

<p align="center">A Paper 26.3 plugin that records its own player statistics in SQLite and exposes them through PlaceholderAPI. Pick the stat that TAB's <code>playerlist-objective</code> shows with one command.</p>

<p align="center">
  <a href="https://github.com/Lapius7/TabScore-plugin/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Lapius7/TabScore-plugin?label=release&style=flat-square&color=e8833a&logo=github"></a>
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-62b47a?style=flat-square">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ed8b00?style=flat-square&logo=openjdk&logoColor=white">
  <a href="https://github.com/Lapius7/TabScore-plugin/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/Lapius7/TabScore-plugin?style=flat-square&color=e3b341&logo=github"></a>
  <a href="https://github.com/Lapius7/TabScore-plugin/commits/main"><img alt="Last commit" src="https://img.shields.io/github/last-commit/Lapius7/TabScore-plugin?style=flat-square&color=3b82f6"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="#commands">Commands</a> ·
  <a href="#placeholders">Placeholders</a> ·
  <a href="#configuration">Configuration</a> ·
  <a href="#notes">Notes</a>
</p>

</div>

---

TabScore counts what players do (blocks mined, mobs killed, distance walked, play time and more) and stores the totals and daily values per player UUID. The numbers come from the plugin's own records, not from the game's built-in statistics. An admin chooses one item with `/tabscore change`, and `%tabscore_value%` returns it for every player. Put that placeholder into TAB and the value appears next to each name in the tab list.

## Features

- **Its own statistics**: totals and daily values per player, stored in SQLite. Past values stay across restarts.
- **Switch with a command**: `/tabscore change mine:diamond_ore:today` changes the tab list number for everyone. The choice survives restarts.
- **Rankings and details**: `/tabscore me`, `top` and `info` show a single value, the top 10, or the full list with today's numbers.
- **Server load placeholders**: entity, chunk, TPS, MSPT, CPU and RAM values that resolve on any thread, so TAB can use them.
- **One-time import**: read the game's own statistics files, including offline players, and add them to the database.
- **Bots excluded**: fake players can be skipped by name, UUID, or by having no connection address.
- **No I/O on the main thread**: events are counted in memory and written to SQLite from another thread.

## Quick start

You need Paper 26.3 and Java 25. PlaceholderAPI is required to use the placeholders; TAB is needed for the tab list display.

1. Download `tabscore-0.1.0.jar` from the [Releases](https://github.com/Lapius7/TabScore-plugin/releases) page and put it in `plugins/`.
2. Install [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) and [TAB](https://github.com/NEZNAMY/TAB) if you have not.
3. Restart the server.
4. In `plugins/TAB/config.yml`, set the placeholder as the `playerlist-objective` value:

```yaml
playerlist-objective:
  enabled: true
  value: "%tabscore_value%"
  fancy-display-default: "%player%"   # match your existing setting
placeholderapi-refresh-intervals:
  default-refresh-interval: 500
  "%tabscore_value%": 1000
```

5. Run `/tab reload`, then choose what to show: `/tabscore change mine`.

`value` must be an integer, and `%tabscore_value%` always returns one.

> [!NOTE]
> The TAB display has not been checked on a live server yet. The commands and placeholders are what the plugin provides; how TAB draws the number depends on your TAB version and settings.

## Commands

`/tabscore` has the alias `/ts`.

| Command | Permission | What it does |
|---|---|---|
| `/tabscore change <item>` | `tabscore.admin` (OP) | Choose the item shown next to names for everyone. Kept after a restart |
| `/tabscore me [item]` | `tabscore.use` (everyone) | Your own value |
| `/tabscore top [item]` | `tabscore.use` | Top 10 players |
| `/tabscore info [player]` | `tabscore.use` | Full list, with today's values |
| `/tabscore reload` | `tabscore.admin` | Reload `config.yml` and `lang.yml` |
| `/tabscore import [confirm]` | OP or console | Import the game's statistics once (cannot be run twice) |

### Item format

`<category>[:<key>][:today|:week]`

Examples: `mine`, `mine:diamond_ore`, `place:stone`, `kill`, `kill:zombie`, `kill:monster`, `deaths`, `deaths:fall`, `distance`, `distance:elytra`, `playtime`, `playtime:active`, `damage_dealt`, `damage_taken:fall`, `mine:stone:today`, `distance:week`. Tab completion also lists block and entity names.

`today` is the current day in the configured time zone. `week` is the last 7 days including today.

<details>
<summary>What is recorded</summary>

Every item is kept per player UUID, as a total and per day.

| Item | Keys | Unit |
|---|---|---|
| `mine` / `place` | per block ID | blocks |
| `kill` | per entity ID, plus `player` `monster` `animal` `other` | mobs |
| `deaths` | per cause | times |
| `damage_dealt` / `damage_taken` | per target / cause | HP (stored in 0.1 HP) |
| `distance` | `walk sprint sneak swim fly elytra boat horse minecart pig strider vehicle climb fall` | **m** (stored in cm; `fall` is not part of the total) |
| `playtime` | `active` (actually playing), `afk`, `imported` | **minutes** (stored in seconds) |
| `logins` | none | times |
| `fish` `breed` `trade` `use` `craft` `drop` `pickup` `consume` `item_break` `chat` `command` `jump` `enchant` | item ID and similar | times / items |

Last login and login count are shown by `/tabscore info`.

</details>

## Placeholders

| Placeholder | Value |
|---|---|
| `%tabscore_value%` | Value of the selected item (**integer only**, no commas) |
| `%tabscore_label%` | Item name, such as `Blocks mined: stone (today)` |
| `%tabscore_selector%` / `%tabscore_unit%` | Item ID / unit |
| `%tabscore_<item>%` | Integer value of any item: `%tabscore_mine:stone%` or `%tabscore_mine_stone_today%` |

Values come from memory for online players only. Offline players return 0, and the database is never read.

<details>
<summary>Server load placeholders</summary>

PlaceholderAPI's `%server_total_entities%` does not resolve when TAB calls it from an async thread. These placeholders return values collected on the main thread and cached, so they resolve from any thread. No player is needed.

| Placeholder | Value |
|---|---|
| `%tabscore_entities%` / `%tabscore_living%` | Entities in all worlds / living entities among them |
| `%tabscore_tiles%` | Tile entities in loaded chunks |
| `%tabscore_chunks%` / `%tabscore_forced%` | Loaded chunks / force-loaded chunks |
| `%tabscore_players%` | Online players |
| `%tabscore_entities_<world>%` / `%tabscore_chunks_<world>%` | Per world, e.g. `%tabscore_entities_world_nether%` |
| `%tabscore_tps1%` `%tabscore_tps5%` `%tabscore_tps15%` | TPS (capped at 20, one decimal) |
| `%tabscore_mspt%` | Average tick time (ms) |
| `%tabscore_cpu%` | Process CPU usage (%) |
| `%tabscore_ram_used%` `%tabscore_ram_alloc%` `%tabscore_ram_max%` | Used / allocated / maximum memory (MB) |

</details>

## Configuration

`plugins/TabScore/config.yml` holds the database path, flush interval, bot exclusion, default item and AFK time. `lang.yml` holds every message in MiniMessage format.

| Key | Default | Meaning |
|---|---|---|
| `database.file` | `data.db` | SQLite file, relative to the plugin folder |
| `flush-interval-seconds` | `30` | How often counts are written. They are also written on logout and shutdown |
| `daily-retention-days` | `400` | Days of daily values to keep |
| `default-metric` | `mine` | Item shown until `/tabscore change` is used |
| `afk-seconds` | `120` | Seconds without movement, view change or action before a player counts as AFK |
| `timezone` | empty | Time zone for day boundaries. Empty uses the server's |
| `bots.exclude` | `true` | Do not record fake players |
| `bots.name-regex` / `bots.names` / `bots.uuids` | empty | Bot names (regex or list) and UUIDs to skip |
| `bots.exclude-no-address` | `true` | Also skip players without a connection address |
| `metrics.interval-seconds` | `1` | How often server load values are collected |
| `metrics.tile-entities` | `true` | Count tile entities by scanning all loaded chunks. Set `false` on servers with many chunks |

## Notes

- Distance and play time are sampled every second. Teleports are not counted as distance.
- Crafting with Shift-click is estimated from the smallest ingredient stack.
- The database has a schema version. Older databases are migrated in order at startup, and a newer one is refused.

> [!IMPORTANT]
> `/tabscore import` reads the game's statistics JSON files (`<world>/players/stats/<uuid>.json`) on another thread, offline players included, and shows progress in chat. Everything is written in one transaction together with the "imported" mark, so a restart in the middle never counts anything twice, and you can run it again.
> The result is an approximation: the game has no "blocks placed" counter, so block *use* counts go into `place`. The game's deaths and damage have no type, so they go into the `imported` key.

## Roadmap

- Check the tab list display on a live server and add screenshots.
