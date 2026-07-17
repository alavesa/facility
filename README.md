# Facility

Main menu / lobby / teams / combat-log system for the **SITE-19** SCP prison RP
server (github.com/alavesa). Paper plugin, targets `paper-api 1.21.4-R0.1-SNAPSHOT`
(Java 21). The server runs a newer MC build but plugins pin the 1.21.4 API.

## What it does

- **New-player lobby lock.** New arrivals (first join, or anyone who hasn't
  Continued this session) are frozen in SPECTATOR at a lobby vantage and shown
  the main menu. Pressing **Continue** returns them to their last logout spot,
  restores their gamemode, and drops them into the world.
- **Teams + LuckPerms ranks.** Config-driven teams (public / private). Joining a
  team sets the player's LuckPerms group. Admins add/remove teams at runtime and
  grant/revoke access to private teams.
- **DeluxeMenus main menu with a full-screen custom background**, plus a
  self-sufficient built-in chest GUI fallback when DeluxeMenus isn't installed.
- **Combat-log system.** PvP tags both players for 15s (a bossbar counts down);
  logging out while tagged is treated as a death, and the death is shown as a
  floating hologram the next time the player Continues.

## Build

```
cd /Users/piia/Facility
mvn -q package       # -> target/Facility-0.1.0.jar
```

## Commands

| Command | Permission | Who |
| --- | --- | --- |
| `/facility continue` | `facility.use` (default true) | Continue button - return to world |
| `/facility teams` | `facility.use` | Open the team selector |
| `/facility team <name>` | `facility.use` | Join a team (public, or granted private) |
| `/facility team add <name> <public\|private> <rank> [ICON]` | `facility.admin` (default op) | Add/update a team, saved to config |
| `/facility team remove <name>` | `facility.admin` | Remove a team |
| `/facility grant <player> <team>` | `facility.admin` | Grant a player a private team |
| `/facility revoke <player> <team>` | `facility.admin` | Revoke private-team access |
| `/facility reload` | `facility.admin` | Reload config.yml |

Alias: `/fac`.

## Server-owner setup

1. Drop `Facility-0.1.0.jar` into `plugins/`.
2. (Optional but recommended) install **DeluxeMenus** and **LuckPerms**.
   - Copy `deluxemenus/mainmenu.yml` and `deluxemenus/team_selector.yml` into
     `plugins/DeluxeMenus/gui_menus/` and `/dm reload`.
   - Without DeluxeMenus the plugin uses its own built-in chest menus.
   - Without LuckPerms the rank grants no-op (logged as a warning).
3. Rebuild the server resource pack so the menu background ships:
   - Add `/Users/piia/Facility/resource-pack` to the `SOURCES` list in
     `/Users/piia/Lab/tools/build-pack.sh`, then run it. The pack namespace is
     `facility` and won't collide with anything.
   - Regenerate the background asset any time with `python3 tools/gen_menu.py`.

## Full-screen background glyph

The menu background is a resource-pack font glyph drawn into the inventory
title (the same trick as `Terminal/tools/gen_gui.py`). See
`resource-pack/assets/facility/font/menu.json`:

- `U+F801` — space provider, advance **-8** (rewinds the title cursor left).
- `U+F802` — space provider, advance **-168** (spare rewind if you extend it).
- `U+E000` — the `menu_bg.png` bitmap (176x222, `ascent: 13` puts its top edge
  at the container's 0,0), painting the full "SITE-19 // MAIN MENU" panel.

`mainmenu.yml`'s `menu_title` embeds `U+F801` then `U+E000` so the background is
painted behind the buttons. The header text is baked into the PNG.
