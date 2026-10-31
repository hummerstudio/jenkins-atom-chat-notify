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
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.USER, 40004, "webhookUrl is not a URL: " + e.getMessage());
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return AtomResult.error(ErrorType.USER, 40005, "webhookUrl must be http(s): " + uri.getScheme());
        }

        String payload = payload(provider, text, channel, username, avatarUrl, markdown);
        context.log("[chat] " + provider + " -> " + redact(uri) + " (" + payload.length() + " bytes)");

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

    /** The wire format each product expects. Kept together so the differences are visible in one place. */
    static String payload(String provider, String text, String channel, String username, String avatar,
                          boolean markdown) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (provider) {
            case "teams" -> {
                body.put("@type", "MessageCard");
                body.put("@context", "http://schema.org/extensions");
                body.put("summary", summary(text));
                body.put("text", text);
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
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) {
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        }
        StringBuilder masked = new StringBuilder();
        for (String pair : query.split("&")) {
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (masked.length() > 0) {
                masked.append('&');
            }
            masked.append(key).append("=***");
        }
        return uri.getScheme() + "://" + uri.getHost() + uri.getPath() + "?" + masked;
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
