#!/usr/bin/env python3
"""Checks the Office 365 card the fake webhook received: title, facts in order, body text."""
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    lines = [line for line in handle.read().strip().splitlines() if line.strip()]

payload = json.loads(lines[-1])
body = payload["body"]
body = json.loads(body) if isinstance(body, str) else body

assert body["@type"] == "MessageCard", body
assert body["themeColor"] == "d13438", body
section = body["sections"][0]
assert section["activityTitle"] == "acme/api #42", section
assert [fact["name"] for fact in section["facts"]] == ["job", "branch", "duration"], section["facts"]
assert section["facts"][1]["value"] == "main", section["facts"]
assert section["text"] == "3 tests, 1 failed", section
print("card ok")
