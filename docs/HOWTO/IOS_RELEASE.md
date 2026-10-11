# iPhone and iPad (AltStore / SideStore)

ZH Commander on iOS is an unsigned `.ipa` that AltStore or SideStore sign with the player's own
Apple ID. There is no App Store or TestFlight build.

## How players install it

1. Install AltStore (<https://altstore.io>, with AltServer on a PC or Mac) or SideStore
   (<https://sidestore.io>).
2. Add the source `https://zerohour.housamkak.com/altstore.json` (AltStore: Browse → Sources → +).
3. Install ZH Commander from the source, open it, enter an activation key. The game data then
   downloads inside the app, into its Documents folder.

Apps signed with a free Apple ID expire after 7 days; AltStore/SideStore refresh them
automatically. New releases appear in the source as updates.

## How a build is made

**Actions → Build iOS → Run workflow** (`.github/workflows/build-ios.yml`, macOS runner):

- builds the `ios-vulkan` preset (iOS 16.4+, MoltenVK from its pinned release);
- packages `GeneralsXZH.ipa` with `scripts/build/ios/package-ios-zh.sh --runtime-only --unsigned
  --ipa`: no game assets, only fonts/`dxvk.conf`/`DefaultOptions.ini` in `<app>/Runtime`, which
  SDL3Main copies into Documents;
- uploads it to the site's R2 bucket (`site/upload-ipa.py`), never to GitHub.

Its version follows `android/app/build.gradle`, so one bump releases both platforms with the
same number.

## Releasing

Tick **release** when running the workflow. The IPA is added to `ios/releases.json` in R2, which
the site serves as the AltStore source (`/altstore.json`, `site/src/ios.ts`). Installed copies are
offered the update by AltStore/SideStore.

## On the device

`GeneralsMD/Code/Main/IOSGate.mm` runs before the engine: activation (same keys and license
server as Android), then the game-data download (license-checked, resumable, SHA-256 per file) on
a loading screen like Android's, then what online play needs (the cross-play marker, the
GeneralsOnline community data, a renewed GeneralsOnline session), then the game.

The game's own menus reach the app through `ZHCommander.h`: the version, update offers, the
support report (SUPPORT, or ZH COMMANDER, SEND A REPORT; it includes the session logs,
`Documents/generals-stderr.log`), mods, the GeneralsOnline sign-in, and an ONLINE screen (ZH
COMMANDER, ADVANCED, or a lobby's refusal) with the account, "Play with PC players" and the
GeneralsOnline data.

What differs from Android:

- **One engine, 60 Hz.** An iOS app carries one executable, so the `ios-vulkan` preset builds the
  engine with `SAGE_HIGH_FPS_SIM` (the PC client's rate). There is no game-speed choice; iPhones
  and iPads play with 60 Hz phones and, with "Play with PC players" on (the default), with PCs.
- **No restart.** An iOS app cannot start itself again: settings, mods and new game data apply the
  next time the player opens the game.
- **Not yet:** App Monitor events, the in-app language packs, the signed update settings
  (`remote_config.ini`; the engine's built-in defaults apply).

Where things are on the device: game data, `gx_pc_compat.txt` and the logs in `Documents`; the
engine's user data (Options.ini, saves, replays, Maps, `GeneralsOnlineGameData/`) in
`Library/Application Support/GeneralsX/GeneralsZH`; the GeneralsOnline session and the PC checksum
seed (`update/pc_exe_crc_seed.txt`) in `Library/Application Support/GeneralsX`.
