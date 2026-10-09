#!/usr/bin/env python3
"""Release notes: read release-notes/<version>.md (format: release-notes/README.md).

  release-notes.py check <version>          exit 1 and say why when the notes are missing or incomplete
  release-notes.py json <version> [out]     the notes as JSON (stdout, or written to out)

JSON: {"version", "date", "en": {"title", "items": [...]}, "ar": {"title", "items": [...]}}
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LANGS = ("en", "ar")
DASHES = ("–", "—")


def parse(version):
    path = os.path.join(ROOT, "release-notes", "%s.md" % version)
    if not os.path.isfile(path):
        raise ValueError("no release notes for %s (write %s)" % (version, os.path.relpath(path, ROOT)))
    with open(path, encoding="utf-8") as f:
        text = f.read().replace("\r\n", "\n")
    meta = {}
    front = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if front:
        for line in front.group(1).splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                meta[key.strip()] = value.strip()
        text = text[front.end():]
    if meta.get("version", version) != version:
        raise ValueError("%s.md says version %s" % (version, meta["version"]))
    notes = {"version": version, "date": meta.get("date", "")}
    sections = re.split(r"^## +(\w+)\s*$", text, flags=re.M)
    for i in range(1, len(sections), 2):
        lang, body = sections[i].lower(), sections[i + 1]
        title = re.search(r"^# +(.+)$", body, re.M)
        items = [m.strip() for m in re.findall(r"^- +(.+)$", body, re.M)]
        notes[lang] = {"title": title.group(1).strip() if title else "", "items": items}
    for lang in LANGS:
        section = notes.get(lang)
        if not section or not section["title"] or not section["items"]:
            raise ValueError("%s.md needs a '## %s' section with a '# title' and '- ' lines" % (version, lang))
        for line in [section["title"]] + section["items"]:
            if any(d in line for d in DASHES):
                raise ValueError("%s.md: no en or em dashes (%s)" % (version, line))
    return notes


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("check", "json"):
        sys.exit(__doc__)
    try:
        notes = parse(sys.argv[2])
    except ValueError as e:
        sys.exit("release notes: %s" % e)
    if sys.argv[1] == "check":
        print("release notes %s: %d + %d lines" % (notes["version"], len(notes["en"]["items"]), len(notes["ar"]["items"])))
        return
    data = json.dumps(notes, ensure_ascii=False, indent=1)
    if len(sys.argv) > 3:
        os.makedirs(os.path.dirname(os.path.abspath(sys.argv[3])), exist_ok=True)
        with open(sys.argv[3], "w", encoding="utf-8") as f:
            f.write(data)
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(data)


if __name__ == "__main__":
    main()
