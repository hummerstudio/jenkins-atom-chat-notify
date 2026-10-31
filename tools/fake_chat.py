#!/usr/bin/env python3
"""A real HTTP endpoint that records what it was sent.

    fake_chat.py <port> <request-log> [--status 200] [--body '{"ok":true}']

Every request is appended to the log as one JSON line with method, path, headers and body, so the smoke test can
assert the envelope each provider is supposed to send.
"""
import http.server
import json
import sys

port = int(sys.argv[1])
log_path = sys.argv[2]
status = 200
body = '{"ok": true}'
args = sys.argv[3:]
while args:
    flag = args.pop(0)
    if flag == "--status":
        status = int(args.pop(0))
    elif flag == "--body":
        body = args.pop(0)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - the name is http.server's contract
        length = int(self.headers.get("Content-Length") or 0)
        payload = self.rfile.read(length).decode("utf-8") if length else ""
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({
                "method": "POST",
                "path": self.path,
                "contentType": self.headers.get("Content-Type"),
                "userAgent": self.headers.get("User-Agent"),
                "body": payload,
            }) + "\n")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, *args):  # keep the smoke output readable
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
