package com.jenkinschina.atom.chatnotify;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Post one message to a chat service.
 *
 * <p>Slack Notification, Global Slack Notifier, DingTalk, 企业微信, Mattermost, Discord, Rocket.Chat and Google Chat
 * all do the same thing with a different envelope. One atom with a {@code provider} switch beats eight near-identical
 * copies — and it means the "200 OK but the message was rejected" cases are handled once:
 * Slack answers {@code {"ok":false}}, DingTalk/企业微信 answer {@code errcode != 0}, Rocket.Chat {@code success:false}.
 */
public class ChatNotifyAtom implements AtomTask {

    /** Every provider this atom can speak; unknown names are refused instead of silently posting nothing. */
    static final java.util.List<String> PROVIDERS = java.util.List.of(
            "slack", "teams", "google-chat", "dingtalk", "wecom", "discord", "mattermost", "rocketchat");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String provider = context.getParam("provider", "slack").trim().toLowerCase(Locale.ROOT);
        String webhookUrl = context.getParam("webhookUrl", "").trim();
        if (provider.equals("dingtalk") && context.getParam("webhookUrl", "").isEmpty()) {
            // the DingTalk plugin calls it accessToken; accept the name people already know
            String token = context.getParam("accessToken", "").trim();
            if (!token.isEmpty()) {
                webhookUrl = "https://oapi.dingtalk.com/robot/send?access_token=" + token;
            }
        }
        String text = context.getParam("text", "");
        String channel = context.getParam("channel", "");
        String username = context.getParam("username", "");
        String avatarUrl = context.getParam("avatarUrl", "");
        String secret = context.getParam("secret", "");
        String threadKey = context.getParam("threadKey", "").trim();
        String cardJson = context.getParam("cardJson", "").trim();
        boolean markdown = context.getBooleanParam("markdown", false);
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 120));

        if (!PROVIDERS.contains(provider)) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "unknown provider '" + provider + "'; known: " + String.join(", ", PROVIDERS));
        }
        if (webhookUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "webhookUrl is required");
        }
        if (text.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "text is required");
        }
        URI uri;
        try {
            uri = URI.create(withDingTalkSignature(provider, webhookUrl, secret));
            // Google Chat answers in a thread only when the request asks for it, and that is a query parameter
            if ("google-chat".equals(provider) && !threadKey.isEmpty()) {
                uri = URI.create(uri + (uri.getQuery() == null ? "?" : "&")
                        + "messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD");
            }
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.USER, 40004, "webhookUrl is not a URL: " + e.getMessage());
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return AtomResult.error(ErrorType.USER, 40005, "webhookUrl must be http(s): " + uri.getScheme());
        }

        String title = context.getParam("title", "");
        String facts = context.getParam("facts", "");
        String color = context.getParam("themeColor", "");
        if (color.isBlank()) {
            color = statusColor(context.getParam("status", ""));   // a result colour needs no configuration
        }

        // mattermost takes a list of channels (or #channel@user); one request per channel, because a single
        // request carrying "a,b" makes the server look for a channel literally named "a,b"
        java.util.List<String> channels = "mattermost".equals(provider) && channel.matches(".*[,;].*")
                ? java.util.Arrays.stream(channel.split("[,;]+")).map(String::trim).filter(v -> !v.isEmpty()).toList()
                : java.util.List.of(channel);

        java.util.List<AtomResult> results = new java.util.ArrayList<>();
        for (String target : channels) {
            String payload = payload(provider, text, target, username, avatarUrl, markdown, title, facts, color,
                    threadKey, cardJson);
            AtomResult result = post(context, provider, uri, payload, timeoutSeconds, failOnError, target);
            if (!"success".equals(result.getStatus())) {
                return result;
            }
            results.add(result);
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        return AtomResult.success()
                .data("provider", provider)
                .data("channelsSent", String.valueOf(results.size()))
                .data("channels", String.join(", ", channels));
    }

    /**
     * One POST to one destination. Returns a success carrying the status and the response, or a failure — the caller
     * only has to know whether the message landed.
     */
    private static AtomResult post(AtomContext context, String provider, URI uri, String payload, int timeoutSeconds,
                                   boolean failOnError, String channel) {
        context.log("[chat] " + provider + (channel.isEmpty() ? "" : " " + channel) + " -> " + redact(uri)
                + " (" + payload.length() + " bytes)");

        HttpResponse<String> response;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "jenkins-atom-chat-notify")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40006,
                    "could not connect to " + redact(uri) + " within " + timeoutSeconds + "s");
        } catch (java.io.IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007,
                    "posting to " + redact(uri) + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AtomResult.error(ErrorType.ENVIRONMENT, 40009, "interrupted while posting to " + redact(uri));
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        String rejection = rejection(provider, status, body);
        if (!rejection.isEmpty() && failOnError) {
            return AtomResult.error(ErrorType.THIRD_PART, 40008, rejection);
        }
        if (!rejection.isEmpty()) {
            context.log("[chat] warning: " + rejection);
        }
        context.log("[chat] HTTP " + status + (body.isBlank() ? "" : " " + excerpt(body)));

        return AtomResult.success()
                .data("provider", provider)
                .data("statusCode", String.valueOf(status))
                .data("accepted", rejection.isEmpty() ? "true" : "false")
                .data("response", excerpt(body))
                .data("payloadBytes", String.valueOf(payload.length()));
    }


    /** {@code name=value} pairs, one per comma or line, in the order the caller wrote them. */
    static java.util.List<Map<String, String>> factList(String facts) {
        java.util.List<Map<String, String>> list = new java.util.ArrayList<>();
        for (String part : facts.split("[,\\n]")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.indexOf('=');
            String name = split < 0 ? trimmed : trimmed.substring(0, split).trim();
            String value = split < 0 ? "" : trimmed.substring(split + 1).trim();
            list.add(Map.of("name", name, "value", value));
        }
        return list;
    }

    /** Red for a failure, amber for unstable, green otherwise: the colour is the first thing anyone reads. */
    static String statusColor(String status) {
        String lowered = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (lowered) {
            case "failure", "failed", "error", "aborted" -> "d13438";
            case "unstable" -> "ffb900";
            case "success", "succeeded", "ok" -> "4caf50";
            default -> "";
        };
    }

    /** The wire format each product expects. Kept together so the differences are visible in one place. */
    static String payload(String provider, String text, String channel, String username, String avatar,
                          boolean markdown) {
        return payload(provider, text, channel, username, avatar, markdown, "", "", "", "", "");
    }

    /**
     * @param title  card title; empty keeps the older plain message
     * @param facts  {@code name=value} pairs, comma or newline separated, shown as a card table
     * @param color  theme colour; empty leaves it to the caller
     */
    static String payload(String provider, String text, String channel, String username, String avatar,
                          boolean markdown, String title, String facts, String color, String threadKey,
                          String cardJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (provider) {
            case "teams" -> {
                body.put("@type", "MessageCard");
                body.put("@context", "http://schema.org/extensions");
                body.put("summary", summary(text));
                if (!color.isBlank()) {
                    body.put("themeColor", color);
                }
                if (!title.isBlank() || !facts.isBlank()) {
                    // the Office 365 connector card: a titled section with a facts table is the shape people
                    // actually configure, and it is what the MessageCard schema was extended for
                    Map<String, Object> section = new LinkedHashMap<>();
                    if (!title.isBlank()) {
                        section.put("activityTitle", title);
                    }
                    if (!facts.isBlank()) {
                        section.put("facts", factList(facts));
                    }
                    section.put("text", text);
                    body.put("sections", java.util.List.of(section));
                } else {
                    body.put("text", text);
                }
            }
            case "dingtalk" -> {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", text));
            }
            case "wecom" -> {
                if (markdown) {
                    body.put("msgtype", "markdown");
                    body.put("markdown", Map.of("content", text));
                } else {
                    body.put("msgtype", "text");
                    body.put("text", Map.of("content", text));
                }
            }
            case "mattermost" -> {
                // StandardMattermostService sends these keys; attachments carry the colour bar and the facts table,
                // with mrkdwn_in so the text inside the attachment is rendered as markdown
                body.put("text", text);
                if (!channel.isEmpty()) {
                    body.put("channel", channel);
                }
                if (!username.isEmpty()) {
                    body.put("username", username);
                }
                if (!avatar.isEmpty()) {
                    body.put("icon_url", avatar);
                }
                if (!color.isBlank() || !title.isBlank() || !facts.isBlank()) {
                    Map<String, Object> attachment = new LinkedHashMap<>();
                    if (!color.isBlank()) {
                        attachment.put("color", color);
                    }
                    if (!title.isBlank()) {
                        attachment.put("title", title);
                    }
                    if (!facts.isBlank()) {
                        attachment.put("fields", factList(facts));
                    }
                    attachment.put("mrkdwn_in", java.util.List.of("text", "fields"));
                    body.put("attachments", java.util.List.of(attachment));
                }
            }
            case "google-chat" -> {
                body.put("text", text);
                if (!threadKey.isBlank()) {
                    body.put("thread", Map.of("threadKey", threadKey));
                }
                if (!cardJson.isBlank()) {
                    try {
                        body.put("cards", Json.parse(cardJson));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("cardJson is not a JSON array: " + e.getMessage());
                    }
                }
                // deliberately no channel: Google Chat routes by webhook URL and ignores the field, so writing it
                // would produce a "sent" that did nothing
            }
            case "discord" -> {
                body.put("content", text);
                if (!username.isEmpty()) {
                    body.put("username", username);
                }
                if (!avatar.isEmpty()) {
                    body.put("avatar_url", avatar);
                }
            }
            default -> {
                body.put("text", text);
                if (!channel.isEmpty()) {
                    body.put("channel", channel);
                }
            }
        }
        return Json.write(body);
    }

    /**
     * Why a message was rejected, or "" when it was accepted. A 200 is not acceptance: Slack, DingTalk, 企业微信 and
     * Rocket.Chat all answer 200 with an error inside the body, which is how a "notification sent" build lies.
     */
    static String rejection(String provider, int status, String body) {
        if (status < 200 || status >= 300) {
            return "HTTP " + status + " from the chat endpoint: " + excerpt(body);
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return ""; // plain-text acknowledgements are fine
        }
        Object parsed;
        try {
            parsed = Json.parse(trimmed);
        } catch (RuntimeException e) {
            return "";
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return "";
        }
        if (Boolean.FALSE.equals(map.get("ok"))) {
            return "the service refused the message: " + stringify(map.get("error"));
        }
        if (Boolean.FALSE.equals(map.get("success"))) {
            return "the service refused the message: " + stringify(map.get("error"));
        }
        Object code = map.get("errcode");
        if (code != null && !"0".equals(String.valueOf(code))) {
            return "the service returned errcode " + code + ": " + stringify(map.get("errmsg"));
        }
        Object reported = map.get("status");
        if (reported instanceof Number number && number.intValue() >= 400) {
            return "the service returned status " + number + ": " + stringify(map.get("message"));
        }
        return "";
    }

    /** DingTalk optionally signs the request: sign = base64(hmacSha256(secret, timestamp + "\n" + secret)). */
    static String withDingTalkSignature(String provider, String url, String secret) {
        if (!provider.equals("dingtalk") || secret == null || secret.isBlank()) {
            return url;
        }
        long timestamp = System.currentTimeMillis();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            String sign = java.net.URLEncoder.encode(
                    java.util.Base64.getEncoder().encodeToString(digest), StandardCharsets.UTF_8);
            return url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            return url;
        }
    }

    /** Tokens in the query string must not reach the console. */
    static String redact(URI uri) {
        String base = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            return base;
        }
        StringBuilder masked = new StringBuilder();
        for (String pair : query.split("&")) {
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (masked.length() > 0) {
                masked.append('&');
            }
            masked.append(key).append("=***");
        }
        return base + "?" + masked;
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static String summary(String text) {
        String first = text.strip().lines().findFirst().orElse("build notification");
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
