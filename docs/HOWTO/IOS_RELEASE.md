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
server as Android), then the game-data download (license-checked, resumable, SHA-256 per file),
then the game. A small button in the game's top corner shares a support report with the session
logs (`Documents/generals-stderr.log`).
