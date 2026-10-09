# Release notes

One file per release, `release-notes/<versionName>.md`, written before the release is built. It is
the only place notes are written: CI checks it, puts it in the app (the in-game "What's new"),
and publishes it to the site (`/changes`, the update offer, the iOS store text, the admin
console). A release without its file does not build.

```
---
version: 0.1.11
date: 2026-10-10
---

## en
# Release notes in the game and on the site
- What changed, for a player, one line each
- Three to six lines

## ar
# ملاحظات الإصدار في اللعبة وعلى الموقع
- نفس النقاط بالعربية
```

- `## en` and `## ar` are both required, each with a `#` title and at least one `-` line.
- Write for players: what they will notice, not how it was done. No em or en dashes.
- `scripts/release/release-notes.py check <version>` says whether a file is complete.
