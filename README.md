# jenkins-atom-chat-notify

把一条消息发到协作工具。

Slack Notification、Global Slack Notifier、DingTalk、企业微信、Mattermost、Discord、Rocket.Chat、Google Chat 做的是同一件事，只是报文外壳不同；一个原子按 `provider` 切换，而不是八份拷贝。

**200 不等于送达**：Slack 回 `{"ok":false}`、钉钉/企微回 `errcode != 0`、Rocket.Chat 回 `success:false`，这些都会被判成失败（`failOnError` 可关）。URL 里的 token 在日志里被遮掉。

```bash
./build.sh && ./tools/smoke.sh   # 起一个真 HTTP 服务器，断言报文与失败路径
```
