# GenPatcher, GenTool and community content: what ZH Commander can use

Investigation of 11/10/2026 (read-only, no code changed). Question from the owner: GenPatcher is
installed on the dev PC and changes the Zero Hour install; does it have anything the phone port can
use (fixes, maps), do we have its source, and what is attainable.

Short answer: GenPatcher itself is closed source with no license, and most of what it does repairs
or patches a Windows install, which a port rebuilt from source does not need. What is worth having
sits around it: the map-list limit fix, Control Bar Pro (MIT, open source), and community maps
(with their authors' permission).

## 1. What is on the dev PC

GenPatcher 1.1.37 (`Documents\Command and Conquer Generals Data\GenPatcher\GenPatcher.exe`) has been
applied to `D:\Games\Command and Conquer Generals Zero Hour\Command and Conquer Generals Zero Hour\`
(`gp.info` = 2). In that folder:

| File | What it is |
|---|---|
| `d3d8.dll`, `d3d8.cfg`, `GenToolUpdater.exe` | GenTool v89 and its map updater (map packs 1v1/2v2/3v3, Maps2024 1v1 to 4v4) |
| `340_ControlBarProZH.big` (25.7 MB), `340_ControlBarPro1080ZH.big` | Control Bar Pro: faction control bar art, mapped-image INIs, `ControlBarScheme.ini`, `InGameUI.ini`, 22 `.wnd` layouts, `GenTool\fullviewport.dat` |
| `!HotkeysLeikezeZH.big`, `!HotkeysLeikezeIndicatorsZH.big` | Leikeze hotkey preset (`generals.csf`) and icon textures with hotkey letters, plus a modified `CommandButton.ini` |
| `CustomContentMaps.big`, `CustomContentMissionMaps.big` | Terrain, textures and models that community maps need; 7 fan-made music tracks for mission maps |
| `WindowZH.big` (with `.bak`) | Only 4 map-select layouts changed (Skirmish, LAN, Online, plain map select): `LISTBOXDATA LENGTH` 300 to 1200, the map-list limit fix |
| `INIZH.big` (edited 30/11/2024) | **Not GenPatcher, a local edit:** `multiplayer.ini` adds 150000 starting money as the default. The `...Zero Hour LOFS\` copy is unedited |
| `generals.exe` (`.bak` kept), `modded.exe` | Patched 1.04 executables |
| `GenLauncher.exe`, `GLM\` | GenLauncher mods: Shockwave, Contra 10, Rise of the Reds, Zero Hour Reborn, Hanpatch, Giant Robot Edition |

Maps: 38 "[RANK]" maps (120 files, 8.7 MB) in `Documents\Command and Conquer Generals Zero Hour Data\Maps`,
fetched by the GenTool updater on 06/10/2026.

## 2. What GenPatcher is

By Legionnaire (legi.cc), since 2019; xezon (GenTool, TheSuperHackers) helped with 1.0 and hosts
files for 2.0. Latest public version 2.14.

- Apply Fixes: runtimes (VC++, DirectX), official patches (Generals 1.08, ZH 1.04), fixed
  executables, registry entries for Steam / EA App / Origin / First Decade, file renames, an optimized
  `options.ini`, high-resolution crash fix, window-mode edge scroll tool, GameRanger / CnCOnline
  setup, firewall rules, cleanup.
- Additional content: GenTool, Control Bar Pro / HD, hotkey presets, a Community Map Pack (4 packs:
  skirmish/MP, missions, AOD/comp-stomp, mod and no-money maps) with its custom-content `.big`
  files, and the map-list fix (300 to 1200).

## 3. Source code and licenses

| Component | Source | License / terms |
|---|---|---|
| GenPatcher | Closed, on purpose (its support page: to prevent malicious copies) | "Free software", no named license, no redistribution grant |
| GenTool | Closed | Nothing granted |
| Control Bar Pro / HD | `github.com/TheSuperHackers/GeneralsControlBar` | MIT (xezon 2021). The art is derived from EA's, so EA's terms still cover it |
| TheSuperHackers community patch (data) | `TheSuperHackers/GeneralsGamePatch` | GPL-3.0 plus `TERMS.txt` with EA's terms: derivatives may not be sold, no C&C music, EA may revoke |
| Leikeze hotkeys | Via GenPatcher | No license found |
| Community maps | Each author | Usually no license: permission per author |

## 4. What the repo already covers

- `scripts/build/android/bundle-game-data.py` skips `!hotkeys*`, `340_controlbarpro*` and
  `customcontent*`, since they change the INI checksum online play compares.
- `AddonCompat.cpp` / `GlobalData.cpp` read `GenTool/fullviewport.dat`, so Control Bar Pro's full
  viewport mode is understood.
- `docs/PATCHES/SAGEPATCH.md`: GenTool's engine bug fixes are in TheSuperHackers' code we build on.
- Loose `.wnd` overrides already ship from `android/app/src/main/assets/gamedata/Window/`.
- Gaps: the map menus still cap at 300 maps (the list box does not grow, `GadgetListBox.cpp`), and the
  bundling script would not catch an edited `INIZH.big` like the one on this PC.

## 5. Assessment for the phone

| Component | Useful? | Attainable? | Risk |
|---|---|---|---|
| Executable patches, runtimes, registry, GameRanger, firewall | No (built from source) | n/a | none |
| GenTool engine fixes, widescreen, zoom, timers | Already in the engine | n/a | none |
| Map-list limit 300 to 1200 | Yes, once players add map packs | Yes: a list box that grows when full (no data edit), or loose `.wnd` overrides of the 4 map menus | Low; `.wnd` files are not in the INI checksum (to confirm) |
| Community maps | Yes, the most valuable item | As an optional in-app download (GenTool style manifest into the user's Maps folder), not bundled | Paid app redistributing others' maps needs permission. Maps are not in the checksum; the engine already transfers a host's map |
| Control Bar Pro | Possibly, a wider HUD for 20:9 | Yes, MIT source | Changes `ControlBarScheme.ini` / `InGameUI.ini`: online play needs the PC side identical. EA-derived art in a paid app. Must fit `GXUiScale` and the touch control bar |
| Leikeze hotkeys and indicator textures | Low on touch | Not licensed | `CommandButton.ini` change breaks the online checksum |
| Custom content `.big` files | Only with the maps that need them | Same as maps | Fan music copyrighted; some files EA-derived |
| GeneralsGamePatch (balance and bug-fix data) | In principle | Source available, but terms forbid selling derivatives | Breaks the checksum with stock 1.04 players |

## 6. Recommended next steps

1. Map-list limit: make the list box grow when full in the engine, or override the 4 map-select
   layouts. Small, no license, online compatible.
2. Guard the bundling script: refuse any `INIZH.big` (and other INI archives) whose hash is not the
   retail one, so the local 150000-money edit can never ship.
3. Optional in-app map downloader: a manifest of curated maps, files into the user's Maps folder.
   Ask the curators (xezon for GenTool's packs, Legionnaire for the Community Map Pack) and the map
   authors before featuring their packs in a paid app.
4. Prototype Control Bar Pro from the MIT repo as an optional 20:9 HUD, tested against the
   `GXUiScale` layouts; off for online matches unless the PC side is known to match.
5. Ask TheSuperHackers whether the community patch data and Control Bar Pro art may be used in a
   paid app. Nothing to port from GenPatcher or GenTool themselves.

## Sources

- legi.cc GenPatcher: https://legi.cc/genpatcher/ and support page https://legi.cc/genpatcher-support/
- GenPatcher v2.14 on ModDB: https://www.moddb.com/mods/genpatcher/downloads/genpatcher-v214
- TheSuperHackers/GeneralsGamePatch: https://github.com/TheSuperHackers/GeneralsGamePatch
- TheSuperHackers/GeneralsControlBar: https://github.com/TheSuperHackers/GeneralsControlBar
- The 1200-map limit (Steam discussion): https://steamcommunity.com/app/2732960/discussions/0/4414173015252265920
