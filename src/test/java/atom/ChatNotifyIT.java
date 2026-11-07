package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chat-notify atom, started the way the runner starts it, against the recording webhook in {@code tools/}.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: each provider's envelope, the Office 365 card and its colour
 * bar, Mattermost's attachments and fan-out, Google Chat's thread key and URL, DingTalk's signature, redaction, the
 * "200 but refused" cases, an HTTP error, and bad input.
 */
class ChatNotifyIT {

    private Path module;
    private final List<Process> servers = new ArrayList<>();
    private int port;
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        start("--body", "{\"ok\":true}");
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
    }

    @Test
    @DisplayName("slack gets a text envelope and says it was accepted")
    void postsToSlack() throws Exception {
        AtomResult run = Atom.of().input("""
                {"provider": "slack", "webhookUrl": "%s", "text": "build 42 ok"}
                """.formatted(url("/services/T000/B000/XXXX"))).run();

        run.assertOk();
        assertTrue(lastBody().contains("\"text\":\"build 42 ok\""), "slack envelope: " + lastBody());
        assertEquals("true", run.field("accepted"), "slack accepted flag");
    }

    @Test
    @DisplayName("teams plain text is a MessageCard without a card section")
    void postsPlainTeamsMessage() throws Exception {
        Atom.of().input("""
                {"provider": "teams", "webhookUrl": "%s", "text": "line1\\nline2"}
                """.formatted(url("/hook"))).run().assertOk();

        assertTrue(lastBody().contains("\"@type\":\"MessageCard\""), "teams envelope: " + lastBody());
        assertTrue(lastBody().contains("\"summary\":\"line1\""), "teams summary: " + lastBody());
        assertFalse(lastBody().contains("\"sections\""),
                "plain text should not grow a card section: " + lastBody());
    }

    @Test
    @DisplayName("a teams card carries the title and facts in order, and red for a failed build")
    void postsATeamsCard() throws Exception {
        Atom.of().input("""
                {"provider": "teams", "webhookUrl": "%s", "text": "3 tests, 1 failed",
                 "title": "acme/api #42", "facts": "job=acme/api,branch=main,duration=1m 12s", "status": "failure"}
                """.formatted(url("/hook"))).run().assertOk();

        String body = lastBody();
        assertTrue(body.contains("\"themeColor\":\"d13438\""), "a failed build should be red: " + body);
        assertTrue(body.contains("\"activityTitle\":\"acme/api #42\""), "card title: " + body);

        Map<String, Object> card = Json.parseObject(body);
        assertEquals("MessageCard", card.get("@type"), body);
        assertEquals("d13438", card.get("themeColor"), body);
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) ((List<?>) card.get("sections")).get(0);
        assertEquals("acme/api #42", section.get("activityTitle"), String.valueOf(section));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) section.get("facts");
        assertEquals(List.of("job", "branch", "duration"), facts.stream().map(f -> f.get("name")).toList(),
                String.valueOf(facts));
        assertEquals("main", facts.get(1).get("value"), String.valueOf(facts));
        assertEquals("3 tests, 1 failed", section.get("text"), String.valueOf(section));
    }

    @Test
    @DisplayName("the result decides the colour bar: green for success, amber for unstable")
    void coloursTheCardByResult() throws Exception {
        Atom.of().input("""
                {"provider": "teams", "webhookUrl": "%s", "text": "green", "status": "success"}
                """.formatted(url("/hook"))).run().assertOk();
        assertTrue(lastBody().contains("\"themeColor\":\"4caf50\""), "a successful build should be green: " + lastBody());

        Atom.of().input("""
                {"provider": "teams", "webhookUrl": "%s", "text": "wobble", "status": "unstable"}
                """.formatted(url("/hook"))).run().assertOk();
        assertTrue(lastBody().contains("\"themeColor\":\"ffb900\""), "an unstable build should be amber: " + lastBody());
    }

    @Test
    @DisplayName("dingtalk signs the URL and the token is redacted from the console")
    void signsDingTalkAndRedacts() throws Exception {
        AtomResult run = Atom.of().input("""
                {"provider": "dingtalk", "webhookUrl": "%s", "secret": "SECxyz", "text": "叮"}
                """.formatted(url("/robot/send?access_token=abc"))).run();

        run.assertOk();
        assertTrue(lastBody().contains("\"msgtype\":\"text\""), "dingtalk envelope: " + lastBody());
        String path = String.valueOf(lastRecord().get("path"));
        assertTrue(path.contains("timestamp="), "dingtalk signature missing: " + path);
        assertTrue(path.contains("sign="), "dingtalk sign missing: " + path);
        assertTrue(run.console().contains("access_token=***"), "the token was not redacted:\n" + run.console());
    }

    @Test
    @DisplayName("wecom sends markdown when asked")
    void postsWecomMarkdown() throws Exception {
        Atom.of().input("""
                {"provider": "wecom", "webhookUrl": "%s", "text": "**done**", "markdown": "true"}
                """.formatted(url("/cgi-bin/webhook/send?key=k"))).run().assertOk();

        assertTrue(lastBody().contains("\"msgtype\":\"markdown\""), "wecom envelope: " + lastBody());
    }

    @Test
    @DisplayName("discord forwards the identity override")
    void postsDiscord() throws Exception {
        Atom.of().input("""
                {"provider": "discord", "webhookUrl": "%s", "text": "hi", "username": "jenkins"}
                """.formatted(url("/api/webhooks/1/x"))).run().assertOk();

        assertTrue(lastBody().contains("\"username\":\"jenkins\""), "discord envelope: " + lastBody());
    }

    @Test
    @DisplayName("rocketchat accepts its own hook path")
    void postsToRocketChat() throws Exception {
        Atom.of().input("""
                {"provider": "rocketchat", "webhookUrl": "%s", "text": "hi"}
                """.formatted(url("/hooks/abc"))).run().assertOk();
    }

    @Test
    @DisplayName("a 200 that says no fails the step — the \"notification sent\" lie")
    void failsOnARefusing200() throws Exception {
        restart("--body", "{\"ok\":false,\"error\":\"channel_not_found\"}");
        AtomResult run = Atom.of().input("""
                {"provider": "slack", "webhookUrl": "%s", "text": "x"}
                """.formatted(url("/hook"))).run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("channel_not_found"),
                "the reason is not reported: " + run.file("output.json"));
    }

    @Test
    @DisplayName("failOnError=false reports the refusal instead of failing")
    void canOptOutOfFailing() throws Exception {
        restart("--body", "{\"ok\":false,\"error\":\"channel_not_found\"}");
        AtomResult run = Atom.of().input("""
                {"provider": "slack", "webhookUrl": "%s", "text": "x", "failOnError": "false"}
                """.formatted(url("/hook"))).run();

        run.assertOk();
        assertEquals("false", run.field("accepted"), "accepted flag should say no");
    }

    @Test
    @DisplayName("an HTTP error is a failure with the status in the message")
    void failsOnAnHttpError() throws Exception {
        restart("--status", "500", "--body", "oops");
        AtomResult run = Atom.of().input("""
                {"provider": "mattermost", "webhookUrl": "%s", "text": "x"}
                """.formatted(url("/hooks/x"))).run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("HTTP 500"), "status not reported: " + run.file("output.json"));
    }

    @Test
    @DisplayName("bad input is refused: empty text, and a provider this atom does not have")
    void refusesBadInput() throws Exception {
        AtomResult noText = Atom.of().input("""
                {"provider": "slack", "webhookUrl": "%s"}
                """.formatted(url("/hook"))).run();
        noText.assertRefused();

        AtomResult unknown = Atom.of().input("""
                {"provider": "nope", "webhookUrl": "%s", "text": "x"}
                """.formatted(url("/hook"))).run();
        unknown.assertRefused();
        assertTrue(unknown.file("output.json").contains("known: slack"),
                "the message should list providers: " + unknown.file("output.json"));
    }

    @Test
    @DisplayName("mattermost sends the plugin's own keys, with the colour bar in an attachment")
    void postsMattermostAttachments() throws Exception {
        AtomResult run = Atom.of().input("""
                {"provider": "mattermost", "webhookUrl": "%s", "text": "deploy done", "channel": "town-square",
                 "username": "jenkins", "avatarUrl": "https://example.com/bot.png", "title": "acme/api #42",
                 "facts": "job=acme/api,result=SUCCESS", "status": "success"}
                """.formatted(url("/hook"))).run();

        run.assertOk();
        String body = lastBody();
        assertTrue(body.contains("\"channel\":\"town-square\""), "mattermost channel: " + body);
        assertTrue(body.contains("\"username\":\"jenkins\""), "mattermost username: " + body);
        assertTrue(body.contains("\"icon_url\":\"https://example.com/bot.png\""), "mattermost icon_url: " + body);
        assertTrue(body.contains("\"color\":\"4caf50\""), "mattermost attachment colour: " + body);
        assertTrue(body.contains("\"mrkdwn_in\":[\"text\",\"fields\"]"), "mattermost mrkdwn_in: " + body);
    }

    @Test
    @DisplayName("\"a,b\" is two requests, not one channel named \"a,b\"")
    void fansOutMattermostChannels() throws Exception {
        AtomResult run = Atom.of().input("""
                {"provider": "mattermost", "webhookUrl": "%s", "text": "fan out", "channel": "alpha,beta"}
                """.formatted(url("/hook"))).run();

        run.assertOk();
        assertEquals(2, Files.readAllLines(log).size(), "two channels must be two requests");
        assertEquals("2", run.field("channelsSent"), run.file("output.json"));
    }

    @Test
    @DisplayName("google-chat carries the thread in the body and the reply option in the URL, and no channel")
    void postsGoogleChatThread() throws Exception {
        AtomResult run = Atom.of().input("""
                {"provider": "google-chat", "webhookUrl": "%s", "text": "reply here", "threadKey": "acme-api",
                 "cardJson": "[{\\"header\\":{\\"title\\":\\"build\\"}}]"}
                """.formatted(url("/hook?key=abc&token=t"))).run();

        run.assertOk();
        String body = lastBody();
        assertTrue(body.contains("\"thread\":{\"threadKey\":\"acme-api\"}"), "google-chat thread: " + body);
        assertTrue(body.contains("\"cards\":[{\"header\""), "google-chat cards: " + body);
        assertFalse(body.contains("\"channel\""), "google-chat must not carry a channel field: " + body);
        String path = String.valueOf(lastRecord().get("path"));
        assertTrue(path.contains("messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD"),
                "the reply option is missing from the URL: " + path);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private Map<?, ?> lastRecord() throws Exception {
        List<String> lines = Files.readAllLines(log);
        return Json.parseObject(lines.get(lines.size() - 1));
    }

    private String lastBody() throws Exception {
        return String.valueOf(lastRecord().get("body"));
    }

    private void restart(String... args) throws Exception {
        start(args);
    }

    private void start(String... args) throws Exception {
        port = freePort();
        log = Files.createTempFile(Path.of(System.getProperty("atom.scratch", "target")), "fake-chat", ".log");
        List<String> command = new ArrayList<>(List.of("python3",
                module.resolve("tools/fake_chat.py").toString(),
                String.valueOf(port), log.toString()));
        command.addAll(List.of(args));
        servers.add(new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        awaitPort(port);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitPort(int port) throws Exception {
        for (int i = 0; i < 200; i++) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("the fake chat endpoint did not listen on " + port);
    }
}
