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
last_body | grep -q '"sections"' && fail "plain text should not grow a card section: $(last_body)"

# teams card: the Office 365 shape, with the build result driving the colour bar
dir=$(post "{\"provider\": \"teams\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\", \"text\": \"3 tests, 1 failed\", \"title\": \"acme/api #42\", \"facts\": \"job=acme/api,branch=main,duration=1m 12s\", \"status\": \"failure\"}")
last_body | grep -q '"themeColor":"d13438"' || fail "a failed build should be red: $(last_body)"
last_body | grep -q '"activityTitle":"acme/api #42"' || fail "card title: $(last_body)"
python3 tools/assert_card.py "$log" || fail "card facts were wrong: $(last_body)"

dir=$(post "{\"provider\": \"teams\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\", \"text\": \"green\", \"status\": \"success\"}")
last_body | grep -q '"themeColor":"4caf50"' || fail "a successful build should be green: $(last_body)"
dir=$(post "{\"provider\": \"teams\", \"webhookUrl\": \"http://127.0.0.1:$port/hook\", \"text\": \"wobble\", \"status\": \"unstable\"}")
last_body | grep -q '"themeColor":"ffb900"' || fail "an unstable build should be amber: $(last_body)"

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

# the 500 server above is the live one; start a normal one again for the envelope checks
kill $server 2>/dev/null; wait $server 2>/dev/null
okport=$((port2 + 2))
python3 tools/fake_chat.py "$okport" "$log" --body '{"ok":true}' & server=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$okport/" && break; sleep 0.1; done

# mattermost: the plugin's own keys, one request per channel
dir=$(post "{\"provider\": \"mattermost\", \"webhookUrl\": \"http://127.0.0.1:$okport/hook\", \"text\": \"deploy done\", \"channel\": \"town-square\", \"username\": \"jenkins\", \"avatarUrl\": \"https://example.com/bot.png\", \"title\": \"acme/api #42\", \"facts\": \"job=acme/api,result=SUCCESS\", \"status\": \"success\"}")
status "$dir" | grep -q success || fail "mattermost: $(cat "$dir/output.json")"
last_body | grep -q '"channel":"town-square"' || fail "mattermost channel: $(last_body)"
last_body | grep -q '"username":"jenkins"' || fail "mattermost username: $(last_body)"
last_body | grep -q '"icon_url":"https://example.com/bot.png"' || fail "mattermost icon_url: $(last_body)"
last_body | grep -q '"color":"4caf50"' || fail "mattermost attachment colour: $(last_body)"
last_body | grep -q '"mrkdwn_in":\["text","fields"\]' || fail "mattermost mrkdwn_in: $(last_body)"

# ...and "a,b" is two requests, not one channel named "a,b"
: >"$log"
dir=$(post "{\"provider\": \"mattermost\", \"webhookUrl\": \"http://127.0.0.1:$okport/hook\", \"text\": \"fan out\", \"channel\": \"alpha,beta\"}")
[ "$(wc -l <"$log" | tr -d ' ')" = "2" ] || fail "two channels must be two requests: $(cat "$log")"
[ "$(output "$dir" channelsSent)" = "2" ] || fail "channelsSent: $(cat "$dir/output.json")"

# google-chat: threadKey goes in the body AND becomes a query parameter; cards pass through; no channel field
: >"$log"
dir=$(post "{\"provider\": \"google-chat\", \"webhookUrl\": \"http://127.0.0.1:$okport/hook?key=abc&token=t\", \"text\": \"reply here\", \"threadKey\": \"acme-api\", \"cardJson\": \"[{\\\"header\\\":{\\\"title\\\":\\\"build\\\"}}]\"}")
status "$dir" | grep -q success || fail "google-chat: $(cat "$dir/output.json")"
last_body | grep -q '"thread":{"threadKey":"acme-api"}' || fail "google-chat thread: $(last_body)"
last_body | grep -q '"cards":\[{"header"' || fail "google-chat cards: $(last_body)"
last_body | grep -q '"channel"' && fail "google-chat must not carry a channel field: $(last_body)"
tail -1 "$log" | python3 -c 'import json,sys; p=json.load(sys.stdin)["path"]; assert "messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD" in p, p' \
  || fail "the reply option is missing from the URL: $(tail -1 "$log")"

echo "smoke ok: 8 envelopes, mattermost attachments+fan-out, google-chat thread+cards, dingtalk signature, redaction, ok:false, HTTP 500, bad input"
