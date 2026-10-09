#!/usr/bin/env python3
"""Publish one release's notes (release-notes/<version>.md) to the site.

Usage: upload-notes.py <version>

They show on /changes, in the app's update offer, in the AltStore source and in the admin
console. CI runs this when it releases; run it by hand to correct notes already published.
The upload token comes from GZH_UPLOAD_TOKEN or ~/.generalszh/license_admin_token; the site
from GZH_SITE_URL.
"""
import importlib.util
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zh_r2  # noqa: E402, next to this script

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SITE = "https://zh-commander.housam-kak20.workers.dev"


def load_notes_tool():
    path = os.path.join(os.path.dirname(HERE), "scripts", "release", "release-notes.py")
    spec = importlib.util.spec_from_file_location("release_notes", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def token():
    value = os.environ.get("GZH_UPLOAD_TOKEN")
    if value:
        return value.strip()
    with open(os.path.expanduser("~/.generalszh/license_admin_token")) as f:
        return f.read().strip()


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    try:
        notes = load_notes_tool().parse(sys.argv[1])
    except ValueError as e:
        sys.exit("release notes: %s" % e)
    site = os.environ.get("GZH_SITE_URL", DEFAULT_SITE).rstrip("/")
    zh_r2.call("POST", "%s/admin/notes" % site, token(), json.dumps(notes, ensure_ascii=False).encode("utf-8"))
    print("published release notes %s" % notes["version"])


if __name__ == "__main__":
    main()
