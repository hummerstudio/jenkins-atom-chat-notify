#!/usr/bin/env bash
# Every provider's envelope, the "200 but refused" cases, redaction, and the failure path.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((18000 + RANDOM % 2000))
python3 tools/fake_chat.py "$port" "$log" --body '{"ok":true}' & server=$!
trap 'kill $server 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done

post() { # post <json-input> <workdir>
  local dir; dir="$(mktemp -d)"
  printf '%s' "$1" >"$dir/input.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

last_body() { tail -1 "$log" | python3 -c 'import json,sys;print(json.load(sys.stdin)["body"])'; }

# slack
dir=$(post "{\"provider\": \"slack\", \"webhookUrl\": \"http://127.0.0.1:$port/services/T000/B000/XXXX\", \"text\": \"build 42 ok\"}"); status "$dir" | grep -q success || fail "slack: $(cat "$dir/output.json")"
last_body | grep -q '"text":"build 42 ok"' || fail "slack envelope: $(last_body)"
[ "$(output "$dir" accepted)" = "true" ] || fail "slack accepted flag"

# teams
dir=$(post "{\"provider\": \"teams\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\", \"text\": \"line1\\nline2\"}")
last_body | grep -q '"@type":"MessageCard"' || fail "teams envelope: $(last_body)"
last_body | grep -q '"summary":"line1"' || fail "teams summary: $(last_body)"

# dingtalk signs the URL when a secret is given
dir=$(post "{\"provider\": \"dingtalk\", \"webhookUrl\": \"http://127.0.0.1:$port/robot/send?access_token=abc\", \"secret\": \"SECxyz\", \"text\": \"叮\"}")
last_body | grep -q '"msgtype":"text"' || fail "dingtalk envelope: $(last_body)"
tail -1 "$log" | grep -q 'timestamp=' || fail "dingtalk signature missing: $(tail -1 "$log")"
tail -1 "$log" | grep -q 'sign=' || fail "dingtalk sign missing"
grep -q "access_token=\*\*\*" "$dir/atom.log" || fail "the token was not redacted: $(grep -F '[chat]' "$dir/atom.log" | head -2)"

# wecom markdown
dir=$(post "{\"provider\": \"wecom\", \"webhookUrl\": \"http://127.0.0.1:$port/cgi-bin/webhook/send?key=k\", \"text\": \"**done**\", \"markdown\": \"true\"}")
last_body | grep -q '"msgtype":"markdown"' || fail "wecom envelope: $(last_body)"

# discord identity override
dir=$(post "{\"provider\": \"discord\", \"webhookUrl\": \"http://127.0.0.1:$port/api/webhooks/1/x\", \"text\": \"hi\", \"username\": \"jenkins\"}")
last_body | grep -q '"username":"jenkins"' || fail "discord envelope: $(last_body)"

# rocket.chat
dir=$(post "{\"provider\": \"rocketchat\", \"webhookUrl\": \"http://127.0.0.1:$port/hooks/abc\", \"text\": \"hi\"}"); status "$dir" | grep -q success || fail "rocketchat: $(cat "$dir/output.json")"

# a 200 that says "no" must fail — this is the "notification sent" lie
kill $server 2>/dev/null; wait $server 2>/dev/null; port2=$((port + 1))
python3 tools/fake_chat.py "$port2" "$log" --body '{"ok":false,"error":"channel_not_found"}' & server=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port2/" && break; sleep 0.1; done
dir=$(post "{\"provider\": \"slack\", \"webhookUrl\": \"http://127.0.0.1:$port2/hook\", \"text\": \"x\"}")
status "$dir" | grep -q error || fail "ok:false must fail the step"
grep -q "channel_not_found" "$dir/output.json" || fail "the reason is not reported: $(cat "$dir/output.json")"

# unless the caller opts out
dir=$(post "{\"provider\": \"slack\", \"webhookUrl\": \"http://127.0.0.1:$port2/hook\", \"text\": \"x\", \"failOnError\": \"false\"}")
status "$dir" | grep -q success || fail "failOnError=false must not fail"
[ "$(output "$dir" accepted)" = "false" ] || fail "accepted flag should say no"

# an http error is a failure with the status in the message
kill $server 2>/dev/null; wait $server 2>/dev/null
python3 tools/fake_chat.py $((port2 + 1)) "$log" --status 500 --body 'oops' & server=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$((port2 + 1))/" && break; sleep 0.1; done
dir=$(post "{\"provider\": \"mattermost\", \"webhookUrl\": \"http://127.0.0.1:$((port2 + 1))/hooks/x\", \"text\": \"x\"}")
status "$dir" | grep -q error || fail "HTTP 500 must fail"
grep -q "HTTP 500" "$dir/output.json" || fail "status not reported: $(cat "$dir/output.json")"

# bad input
dir=$(post "{\"provider\": \"slack\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\"}"); status "$dir" | grep -q error || fail "empty text must be refused"
dir=$(post "{\"provider\": \"nope\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\", \"text\": \"x\"}"); status "$dir" | grep -q error || fail "unknown provider must be refused"
grep -q "known: slack" "$dir/output.json" || fail "the message should list providers"

echo "smoke ok: 8 envelopes, dingtalk signature, redaction, ok:false, HTTP 500, bad input"
