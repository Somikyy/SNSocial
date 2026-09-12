/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import network.somikyy.snsocial.core.ClaimState;
import network.somikyy.snsocial.core.Colors;
import network.somikyy.snsocial.core.FmSocialRewardImport;
import network.somikyy.snsocial.core.LinkCodeService;
import network.somikyy.snsocial.core.LinkService;
import network.somikyy.snsocial.core.MemoryStorage;
import network.somikyy.snsocial.core.Messages;
import network.somikyy.snsocial.core.MiniJson;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.RewardDef;
import network.somikyy.snsocial.core.RewardEngine;
import network.somikyy.snsocial.core.StatusCache;
import network.somikyy.snsocial.core.SubscriptionStatus;
import network.somikyy.snsocial.core.TelegramApi;
import network.somikyy.snsocial.core.VkApi;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The offline self-test: every parsing and decision path in core/, no network, no server,
 * no JUnit. Responses are canned strings shaped exactly like the real APIs answer
 * (SPEC §4.1-4.2); a rule without a fixture here does not count as done.
 *
 * <p>Compiled by tools/offline/selftest.sh against the built core classes; never ships.
 */
public final class CoreSelfTest {

    private static int passed = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            FAILURES.add(name);
        }
    }

    public static void main(String[] args) throws Exception {
        // Cyrillic in assertions and messages: make the console UTF-8 regardless of the OS.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        miniJson();
        telegramInterpret();
        telegramUpdates();
        vkInterpret();
        vkLongPoll();
        rewardEngine();
        linkCodes();
        linkFlow();
        messages();
        colours();
        importer();
        statusCache();

        out.println();
        if (FAILURES.isEmpty()) {
            out.println("OK: " + passed + " assertions");
            return;
        }
        out.println("FAILED " + FAILURES.size() + " of " + (passed + FAILURES.size()) + ":");
        for (String failure : FAILURES) {
            out.println("  ✗ " + failure);
        }
        System.exit(1);
    }

    // ---------------------------------------------------------------------------- MiniJson

    private static void miniJson() {
        Map<String, Object> doc = MiniJson.parseObject(
                "{\"ok\":true,\"result\":{\"id\":5000000001,\"name\":\"Игрок \\u0410\\n\","
                        + "\"tags\":[1,2.5,null,false],\"nested\":{\"deep\":\"кириллица=да\"}}}");
        check("json: bool", MiniJson.asBool(doc, "ok", false));
        Map<String, Object> result = MiniJson.obj(doc, "result");
        check("json: long above 2^32 stays exact",
                MiniJson.asLong(result, "id", -1) == 5_000_000_001L);
        check("json: escapes and unicode",
                "Игрок А\n".equals(MiniJson.str(result, "name")));
        check("json: array shape", MiniJson.list(result, "tags").size() == 4);
        check("json: nested object",
                "кириллица=да".equals(MiniJson.str(MiniJson.obj(result, "nested"), "deep")));

        check("json: malformed throws", throwsJson("{\"a\":"));
        check("json: trailing garbage throws", throwsJson("{} extra"));
        check("json: bare word throws", throwsJson("nonsense"));
    }

    private static boolean throwsJson(String text) {
        try {
            MiniJson.parse(text);
            return false;
        } catch (MiniJson.JsonException e) {
            return true;
        }
    }

    // ---------------------------------------------------- Telegram: getChatMember statuses

    private static void telegramInterpret() {
        check("tg: member = subscribed", tgStatus("member") == SubscriptionStatus.SUBSCRIBED);
        check("tg: creator = subscribed", tgStatus("creator") == SubscriptionStatus.SUBSCRIBED);
        check("tg: administrator = subscribed",
                tgStatus("administrator") == SubscriptionStatus.SUBSCRIBED);
        check("tg: left = not subscribed",
                tgStatus("left") == SubscriptionStatus.NOT_SUBSCRIBED);
        check("tg: kicked = not subscribed",
                tgStatus("kicked") == SubscriptionStatus.NOT_SUBSCRIBED);
        check("tg: future unknown status = UNKNOWN",
                tgStatus("hologram") == SubscriptionStatus.UNKNOWN);

        check("tg: restricted with is_member=true = subscribed",
                TelegramApi.interpretChatMember("{\"ok\":true,\"result\":{\"status\":"
                        + "\"restricted\",\"is_member\":true}}")
                        == SubscriptionStatus.SUBSCRIBED);
        check("tg: restricted with is_member=false = not subscribed",
                TelegramApi.interpretChatMember("{\"ok\":true,\"result\":{\"status\":"
                        + "\"restricted\",\"is_member\":false}}")
                        == SubscriptionStatus.NOT_SUBSCRIBED);

        check("tg: 400 user not found = confirmed absence (SPEC §5)",
                TelegramApi.interpretChatMember("{\"ok\":false,\"error_code\":400,"
                        + "\"description\":\"Bad Request: user not found\"}")
                        == SubscriptionStatus.NOT_SUBSCRIBED);
        check("tg: 429 rate limit = UNKNOWN, never a revoke",
                TelegramApi.interpretChatMember("{\"ok\":false,\"error_code\":429,"
                        + "\"description\":\"Too Many Requests: retry after 5\","
                        + "\"parameters\":{\"retry_after\":5}}")
                        == SubscriptionStatus.UNKNOWN);
        check("tg: chat not found = UNKNOWN (misconfig, not the player's fault)",
                TelegramApi.interpretChatMember("{\"ok\":false,\"error_code\":400,"
                        + "\"description\":\"Bad Request: chat not found\"}")
                        == SubscriptionStatus.UNKNOWN);
        check("tg: garbage = UNKNOWN",
                TelegramApi.interpretChatMember("<html>502</html>")
                        == SubscriptionStatus.UNKNOWN);
    }

    private static SubscriptionStatus tgStatus(String status) {
        return TelegramApi.interpretChatMember(
                "{\"ok\":true,\"result\":{\"status\":\"" + status + "\"}}");
    }

    // -------------------------------------------------------------- Telegram: getUpdates

    private static void telegramUpdates() throws Exception {
        TelegramApi.Updates batch = TelegramApi.parseUpdates("{\"ok\":true,\"result\":["
                + "{\"update_id\":101,\"message\":{\"date\":1754000000,"
                +   "\"from\":{\"id\":5000000001},\"chat\":{\"id\":5000000001},"
                +   "\"text\":\"/start ABC234\"}},"
                + "{\"update_id\":102,\"edited_message\":{\"text\":\"skip me\"}},"
                + "{\"update_id\":103,\"message\":{\"date\":1754000001,"
                +   "\"from\":{\"id\":42},\"chat\":{\"id\":42}}}"
                + "]}", 0);
        check("tg updates: only real text messages pass", batch.messages().size() == 1);
        check("tg updates: offset advances over skipped updates too",
                batch.nextOffset() == 104);
        TelegramApi.Update update = batch.messages().get(0);
        check("tg updates: from.id survives as long", update.fromId() == 5_000_000_001L);
        check("tg updates: text intact", "/start ABC234".equals(update.text()));

        check("tg updates: ok:false throws for the poller's backoff", throwsIo(() ->
                TelegramApi.parseUpdates("{\"ok\":false,\"error_code\":409,"
                        + "\"description\":\"Conflict: another getUpdates\"}", 0)));

        check("tg getMe: username extracted",
                "SNSocialBot".equals(TelegramApi.parseBotUsername(
                        "{\"ok\":true,\"result\":{\"id\":1,\"username\":\"SNSocialBot\"}}")));
        check("tg getMe: error = null",
                TelegramApi.parseBotUsername("{\"ok\":false}") == null);
    }

    // ------------------------------------------------------------------ VK: groups.isMember

    private static void vkInterpret() {
        check("vk: response 1 = subscribed",
                VkApi.interpretIsMember("{\"response\":1}") == SubscriptionStatus.SUBSCRIBED);
        check("vk: response 0 = not subscribed",
                VkApi.interpretIsMember("{\"response\":0}")
                        == SubscriptionStatus.NOT_SUBSCRIBED);
        check("vk: extended member:1 = subscribed",
                VkApi.interpretIsMember("{\"response\":{\"member\":1}}")
                        == SubscriptionStatus.SUBSCRIBED);
        check("vk: error 6 rate limit = UNKNOWN",
                VkApi.interpretIsMember("{\"error\":{\"error_code\":6,\"error_msg\":"
                        + "\"Too many requests per second\"}}")
                        == SubscriptionStatus.UNKNOWN);
        check("vk: garbage = UNKNOWN",
                VkApi.interpretIsMember("oops") == SubscriptionStatus.UNKNOWN);
    }

    // ------------------------------------------------------------------- VK: long poll

    private static void vkLongPoll() throws Exception {
        VkApi.LongPollServer server = VkApi.parseLongPollServer("{\"response\":{"
                + "\"key\":\"k123\",\"server\":\"https://lp.vk.com/wh1\",\"ts\":\"7\"}}");
        check("vk lp: server parsed", "https://lp.vk.com/wh1".equals(server.server())
                && "k123".equals(server.key()) && "7".equals(server.ts()));
        check("vk lp: API error carries VK's own message", throwsIo(() ->
                VkApi.parseLongPollServer("{\"error\":{\"error_code\":15,\"error_msg\":"
                        + "\"Access denied: no access to call this method\"}}")));

        VkApi.PollResult result = VkApi.parsePoll("{\"ts\":\"8\",\"updates\":["
                + "{\"type\":\"message_new\",\"object\":{\"message\":{\"from_id\":321,"
                +   "\"peer_id\":321,\"text\":\"ABC234\"},\"client_info\":{}}},"
                + "{\"type\":\"message_new\",\"object\":{\"message\":{\"from_id\":-9000,"
                +   "\"peer_id\":321,\"text\":\"группа пишет\"}}},"
                + "{\"type\":\"wall_post_new\",\"object\":{}}"
                + "]}");
        check("vk lp: human message_new passes", result.messages().size() == 1);
        check("vk lp: from_id and text intact",
                result.messages().get(0).fromId() == 321
                        && "ABC234".equals(result.messages().get(0).text()));
        check("vk lp: ts advances", "8".equals(result.ts()));

        // Long Poll below 5.103: message fields sit straight in "object", no wrapper.
        // The version is a dropdown in the community settings - the admin's choice, not ours.
        VkApi.PollResult legacy = VkApi.parsePoll("{\"ts\":\"12\",\"updates\":["
                + "{\"type\":\"message_new\",\"object\":{\"from_id\":555,\"peer_id\":555,"
                + "\"text\":\"CRRE25\"}}]}");
        check("vk lp: pre-5.103 event shape still parses",
                legacy.messages().size() == 1
                        && legacy.messages().get(0).fromId() == 555
                        && "CRRE25".equals(legacy.messages().get(0).text()));

        check("vk lp: failed:1 keeps new ts",
                VkApi.parsePoll("{\"failed\":1,\"ts\":9}").failed() == 1);
        check("vk lp: failed:2 demands a new server",
                VkApi.parsePoll("{\"failed\":2}").failed() == 2);
        check("vk lp: no ts and no failed = broken response", throwsIo(() ->
                VkApi.parsePoll("{\"updates\":[]}")));
    }

    // -------------------------------------------------------------------- reward engine

    private static void rewardEngine() {
        UUID uuid = UUID.nameUUIDFromBytes("tester".getBytes(StandardCharsets.UTF_8));
        RewardDef subscribe = reward("tg_sub", RewardDef.Type.SUBSCRIBE, 0, false);
        RewardDef reclaimable = reward("tg_re", RewardDef.Type.SUBSCRIBE, 0, true);
        RewardDef periodic = reward("daily", RewardDef.Type.PERIODIC, 24, false);

        PlayerLinks unlinked = PlayerLinks.none(uuid);
        PlayerLinks linked = new PlayerLinks(uuid, 5_000_000_001L, null);
        Map<Network, SubscriptionStatus> subscribed =
                Map.of(Network.TELEGRAM, SubscriptionStatus.SUBSCRIBED);
        Map<Network, SubscriptionStatus> gone =
                Map.of(Network.TELEGRAM, SubscriptionStatus.NOT_SUBSCRIBED);
        Map<Network, SubscriptionStatus> outage =
                Map.of(Network.TELEGRAM, SubscriptionStatus.UNKNOWN);

        ClaimState fresh = ClaimState.fresh("tg_sub");
        long now = 1_754_000_000_000L;

        check("engine: not linked → NEED_LINK", RewardEngine.availability(
                subscribe, unlinked, subscribed, fresh, now).state()
                == RewardEngine.State.NEED_LINK);
        check("engine: confirmed unsubscribed → NEED_SUBSCRIBE", RewardEngine.availability(
                subscribe, linked, gone, fresh, now).state()
                == RewardEngine.State.NEED_SUBSCRIBE);
        check("engine: check failed → paused, not denied", RewardEngine.availability(
                subscribe, linked, outage, fresh, now).state()
                == RewardEngine.State.CHECK_FAILED);
        check("engine: subscribed → AVAILABLE", RewardEngine.availability(
                subscribe, linked, subscribed, fresh, now).state()
                == RewardEngine.State.AVAILABLE);

        ClaimState claimed = fresh.afterClaim(now);
        check("engine: subscribe claimed once → ALREADY_CLAIMED", RewardEngine.availability(
                subscribe, linked, subscribed, claimed, now + 1).state()
                == RewardEngine.State.ALREADY_CLAIMED);

        ClaimState revoked = claimed.afterRevoke(now + 10);
        check("engine: revoked non-reclaimable → LOCKED forever", RewardEngine.availability(
                subscribe, linked, subscribed, revoked, now + 20).state()
                == RewardEngine.State.LOCKED);
        check("engine: revoked reclaimable → AVAILABLE again", RewardEngine.availability(
                reclaimable, linked, subscribed, revoked, now + 20).state()
                == RewardEngine.State.AVAILABLE);

        ClaimState daily = ClaimState.fresh("daily").afterClaim(now);
        RewardEngine.Availability cooling = RewardEngine.availability(
                periodic, linked, subscribed, daily, now + 3_600_000L);
        check("engine: periodic inside period → COOLDOWN",
                cooling.state() == RewardEngine.State.COOLDOWN);
        check("engine: cooldown remainder is exact",
                cooling.remainingMillis() == 23L * 3_600_000L);
        check("engine: periodic after period → AVAILABLE", RewardEngine.availability(
                periodic, linked, subscribed, daily, now + 25L * 3_600_000L).state()
                == RewardEngine.State.AVAILABLE);

        check("engine: revoke on confirmed unsubscribe",
                RewardEngine.shouldRevoke(subscribe, gone, claimed));
        check("engine: NO revoke on UNKNOWN - the iron rule",
                !RewardEngine.shouldRevoke(subscribe, outage, claimed));
        check("engine: no revoke without an active claim",
                !RewardEngine.shouldRevoke(subscribe, gone, fresh));
        check("engine: no revoke for periodic rewards",
                !RewardEngine.shouldRevoke(periodic, gone, daily));

        check("engine: %player% and %uuid% expand",
                RewardEngine.expandCommand("give %player% diamond %uuid%", "Somikyy", uuid)
                        .equals("give Somikyy diamond " + uuid));
    }

    private static RewardDef reward(String id, RewardDef.Type type, int hours,
                                    boolean reclaimable) {
        return new RewardDef(id, Set.of(Network.TELEGRAM), type, hours,
                List.of("give %player% diamond 1"), List.of(), reclaimable, false,
                id, List.of(), "DIAMOND", -1);
    }

    // ----------------------------------------------------------------------- link codes

    private static void linkCodes() {
        UUID player = UUID.nameUUIDFromBytes("codes".getBytes(StandardCharsets.UTF_8));
        LinkCodeService codes = new LinkCodeService(600_000);
        long now = 1_754_000_000_000L;

        String code = codes.issue(player, "Somikyy", Network.TELEGRAM, now);
        check("codes: 6 chars, no confusable symbols",
                code.length() == 6 && !code.matches(".*[0OI1L].*"));
        check("codes: '/start CODE' redeems (deep link flow)",
                codes.redeem("/start " + code, Network.TELEGRAM, now + 1000) != null);
        check("codes: single use",
                codes.redeem(code, Network.TELEGRAM, now + 2000) == null);

        String expired = codes.issue(player, "Somikyy", Network.TELEGRAM, now);
        check("codes: dead after TTL",
                codes.redeem(expired, Network.TELEGRAM, now + 600_001) == null);

        String vkCode = codes.issue(player, "Somikyy", Network.VK, now);
        check("codes: wrong network never matches",
                codes.redeem(vkCode, Network.TELEGRAM, now + 1000) == null);
        check("codes: case- and whitespace-tolerant",
                codes.redeem("  " + vkCode.toLowerCase() + "  ", Network.VK, now + 1000)
                        != null);

        String first = codes.issue(player, "Somikyy", Network.TELEGRAM, now);
        String second = codes.issue(player, "Somikyy", Network.TELEGRAM, now);
        check("codes: reissue kills the previous code",
                codes.redeem(first, Network.TELEGRAM, now + 1000) == null
                        && codes.redeem(second, Network.TELEGRAM, now + 1000) != null);
    }

    // ----------------------------------------------------------- link flow over storage

    private static void linkFlow() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        LinkCodeService codes = new LinkCodeService(600_000);
        LinkService links = new LinkService(codes, storage);
        long now = 1_754_000_000_000L;
        UUID alice = UUID.nameUUIDFromBytes("alice".getBytes(StandardCharsets.UTF_8));
        UUID bob = UUID.nameUUIDFromBytes("bob".getBytes(StandardCharsets.UTF_8));

        String code = codes.issue(alice, "Alice", Network.TELEGRAM, now);
        LinkService.Outcome outcome =
                links.tryRedeem(Network.TELEGRAM, 5_000_000_001L, code, now + 1);
        check("flow: linked outcome",
                outcome instanceof LinkService.Outcome.Linked l
                        && l.player().equals(alice));
        check("flow: link persisted",
                storage.links(alice).telegramId() == 5_000_000_001L);
        check("flow: reverse lookup finds the player",
                storage.playerBySocialId(Network.TELEGRAM, 5_000_000_001L)
                        .orElseThrow().equals(alice));

        String bobCode = codes.issue(bob, "Bob", Network.TELEGRAM, now);
        LinkService.Outcome conflict =
                links.tryRedeem(Network.TELEGRAM, 5_000_000_001L, bobCode, now + 2);
        check("flow: same account for a second player → Conflict with the holder's name",
                conflict instanceof LinkService.Outcome.Conflict c
                        && "Alice".equals(c.otherPlayerName()));
        check("flow: conflicting link NOT written",
                storage.links(bob).telegramId() == null);

        check("flow: garbage text → BadCode",
                links.tryRedeem(Network.TELEGRAM, 77, "привет боту", now + 3)
                        instanceof LinkService.Outcome.BadCode);

        storage.putClaim(alice, ClaimState.fresh("tg_sub").afterClaim(now));
        storage.unlink(alice, Network.TELEGRAM);
        check("flow: unlink clears the link",
                storage.links(alice).telegramId() == null);
        check("flow: unlink KEEPS claim history (anti-abuse memory)",
                storage.claim(alice, "tg_sub").everClaimed());
    }

    // ------------------------------------------------------------------------- messages

    private static void messages() throws Exception {
        Messages messages = Messages.bundled();
        check("messages: bundles loaded", !messages.keys().isEmpty());
        List<String> missing = new ArrayList<>();
        for (String key : messages.keys()) {
            if (!messages.has(key, true)) {
                missing.add("ru:" + key);
            }
            if (!messages.has(key, false)) {
                missing.add("en:" + key);
            }
        }
        check("messages: every key exists in BOTH ru and en, missing=" + missing,
                missing.isEmpty());
        // 'yes' and 'no' are YAML booleans when written bare; quoting them in the bundle is
        // the difference between a working key and a text nobody can find.
        eq("messages: ru is really russian", "да", messages.get("word.yes", true));
        eq("messages: quoted key reads back under its plain name", "нет",
                messages.get("word.no", true));
        check("messages: placeholders fill by name",
                messages.get("bot.linked", true, "player", "Somikyy").contains("Somikyy"));
        eq("messages: missing key degrades to the key itself", "no.such.key",
                messages.get("no.such.key", true));
        // The bot contract: bot.* keys carry no MiniMessage markup.
        List<String> marked = new ArrayList<>();
        for (String key : messages.keys()) {
            if (key.startsWith("bot.") && messages.get(key, true).matches(".*<[a-z#_].*")) {
                marked.add(key);
            }
        }
        check("messages: bot.* texts are markup-free: " + marked, marked.isEmpty());

        // The prefix contract. It is one key, it is resolved on every lookup, and emptying it
        // is the supported way to take the plugin name out of every line - that is the whole
        // reason the key exists, so it gets fixtures rather than a comment.
        check("messages: prefix ships in both languages",
                messages.has("prefix", true) && messages.has("prefix", false));
        List<String> unresolved = new ArrayList<>();
        for (String key : messages.keys()) {
            if (messages.get(key, true).contains("{prefix}")
                    || messages.get(key, false).contains("{prefix}")) {
                unresolved.add(key);
            }
        }
        check("messages: no {prefix} survives a lookup: " + unresolved, unresolved.isEmpty());
        check("messages: prefix is really in front", messages.get("cmd.no-permission", true)
                .startsWith(messages.get("prefix", true)));
        // The GUI hands an already-coloured reward title in as {name}; the template must stay
        // a bare placeholder or the title would be wrapped in someone else's colour.
        eq("messages: gui.item-name passes the reward title through", "{name}",
                messages.get("gui.item-name", true));
        eq("messages: gui.item-lore-line passes the lore line through", "{line}",
                messages.get("gui.item-lore-line", true));
        // The stamp install() copies into the admin's file along with everything else. Without
        // it the language flag would go quiet forever instead of being reported once.
        eq("messages: the ru bundle says so itself", "ru", messages.get("language", true));
        eq("messages: the en bundle too", "en", messages.get("language", false));

        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("snsocial-msg");
        try {
            // An admin who emptied the prefix must get an empty prefix, not the default back.
            java.nio.file.Path file = folder.resolve("messages.yml");
            java.nio.file.Files.writeString(file,
                    "prefix: ''\ncmd:\n  no-permission: '&cнельзя'\n",
                    StandardCharsets.UTF_8);
            Messages edited = Messages.load(file);
            eq("messages: empty prefix stays empty", "", edited.get("prefix", true));
            eq("messages: override wins and keeps its own codes", "&cнельзя",
                    edited.get("cmd.no-permission", true));
            check("messages: untouched key still falls back to the bundle",
                    edited.get("admin.reload.done", true).contains("перезагружен"));

            // "prefix:" with nothing after it is the same thing as "prefix: ''" - the server
            // owner who asked for this will write whichever of the two occurs to them.
            java.nio.file.Path blank = folder.resolve("blank.yml");
            java.nio.file.Files.writeString(blank, "prefix:\n", StandardCharsets.UTF_8);
            Messages blanked = Messages.load(blank);
            eq("messages: a prefix key with no value at all is empty too", "",
                    blanked.get("prefix", true));
            List<String> branded = new ArrayList<>();
            for (String key : blanked.keys()) {
                if (blanked.get(key, true).contains("Social</#00E1FF>")
                        || blanked.get(key, false).contains("Social</#00E1FF>")) {
                    branded.add(key);
                }
            }
            check("messages: emptying prefix clears the plugin name from every line: " + branded,
                    branded.isEmpty());

            // Migration off the 26.8.1 .txt format: the admin's line survives, and so does the
            // documentation around it - the file is meant to stay readable, not become a dump.
            java.nio.file.Path fresh = folder.resolve("fresh");
            java.nio.file.Files.createDirectories(fresh);
            java.nio.file.Files.writeString(fresh.resolve("messages-ru.txt"),
                    "# комментарий\nadmin.reload.done=<green>перечитано</green>\n"
                            + "bot.linked=привязан {player}\n"
                            + "prefix=<gold>МойСервер</gold>\n",
                    StandardCharsets.UTF_8);
            List<String> log = Messages.install(fresh, true);
            check("messages: install reports what it did: " + log, log.size() == 2);
            check("messages: install counts the carried lines: " + log,
                    log.get(1).contains("Перенесено строк из messages-ru.txt: 3"));
            String written = java.nio.file.Files.readString(
                    fresh.resolve("messages.yml"), StandardCharsets.UTF_8);
            check("messages: migrated file keeps its comments",
                    written.contains("# ---------- время"));
            check("messages: migrated value landed",
                    written.contains("'<green>перечитано</green>'"));
            check("messages: nested migrated value landed",
                    written.contains("'привязан {player}'"));
            Messages migrated = Messages.load(fresh.resolve("messages.yml"));
            eq("messages: migrated text is what the plugin reads", "<green>перечитано</green>",
                    migrated.get("admin.reload.done", true));
            eq("messages: migrated nested text too", "привязан {player}",
                    migrated.get("bot.linked", true));
            // In 26.8.1 the space after the brand was typed into every line; carrying the
            // prefix over has to carry that space too, or every line comes out glued.
            eq("messages: a migrated prefix keeps its trailing space",
                    "<gold>МойСервер</gold> ", migrated.get("prefix", true));
            check("messages: and the bundled lines start with it",
                    migrated.get("cmd.usage", true).startsWith("<gold>МойСервер</gold> <gray>"));
            check("messages: second install leaves the admin's file alone",
                    Messages.install(fresh, true).isEmpty());

            // The file carries the language it was written in, and the plugin says so out loud
            // when config.yml starts asking for the other one: from the first start on, the
            // file is what chooses the language, and silence there reads as a broken flag.
            eq("messages: the installed file keeps the stamp of its language", "ru",
                    migrated.get("language", true));
            check("messages: a russian file under language: en is reported",
                    migrated.languageMismatch(false) != null);
            check("messages: and the line names the file and the flag",
                    migrated.languageMismatch(false).contains("messages.yml")
                            && migrated.languageMismatch(false).contains("en"));
            check("messages: a file in the language that was asked for says nothing",
                    migrated.languageMismatch(true) == null);
            check("messages: the bundles alone accuse nobody",
                    messages.languageMismatch(false) == null);

            // Only the language being installed is carried over. The other file is left on
            // disk, so the admin who edited both is told which half stayed behind.
            java.nio.file.Path both = folder.resolve("both");
            java.nio.file.Files.createDirectories(both);
            java.nio.file.Files.writeString(both.resolve("messages-ru.txt"),
                    "prefix=<gold>Сервер</gold>\n", StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(both.resolve("messages-en.txt"),
                    "prefix=<gold>Server</gold>\n", StandardCharsets.UTF_8);
            List<String> bothLog = Messages.install(both, true);
            check("messages: the second .txt is named, not carried: " + bothLog,
                    bothLog.size() == 3 && bothLog.get(2).contains("messages-en.txt"));
            eq("messages: and the carried one is still the chosen language",
                    "<gold>Сервер</gold> ",
                    Messages.load(both.resolve("messages.yml")).get("prefix", true));

            // An apostrophe is an ordinary thing to write; it must not end the value early.
            java.nio.file.Path quoted = folder.resolve("quoted.yml");
            java.nio.file.Files.writeString(quoted,
                    "bot:\n  bad-code: 'yes, it''s me'\n", StandardCharsets.UTF_8);
            eq("messages: doubled apostrophe unescapes", "yes, it's me",
                    Messages.load(quoted).get("bot.bad-code", false));
            check("messages: a file written before the stamp existed is not accused",
                    Messages.load(quoted).languageMismatch(false) == null);
        } finally {
            deleteTree(folder);
        }
    }

    // -------------------------------------------------------------------------- colours

    private static void colours() {
        // Every notation an admin might already have in their fingers.
        eq("colors: legacy code", "<reset><red>привет", Colors.toMiniMessage("&cпривет"));
        eq("colors: legacy via section sign", "<reset><green>ок", Colors.toMiniMessage("§aок"));
        eq("colors: short hex", "<reset><#7b2fff>текст", Colors.toMiniMessage("&#7B2FFFтекст"));
        eq("colors: long spigot hex", "<reset><#7b2fff>текст",
                Colors.toMiniMessage("&x&7&B&2&F&F&Fтекст"));
        eq("colors: decoration and reset", "<bold>жирный<reset>обычный",
                Colors.toMiniMessage("&lжирный&rобычный"));
        eq("colors: a lone ampersand is text", "Том & Джерри",
                Colors.toMiniMessage("Том & Джерри"));
        eq("colors: an unknown code is text", "&q", Colors.toMiniMessage("&q"));

        // Legacy semantics: a colour clears bold, but only where there is no markup to break.
        eq("colors: pure legacy resets like old chat", "<reset><red><bold>Ж <reset><green>О",
                Colors.toMiniMessage("&c&lЖ &aО"));
        eq("colors: mixed markup closes nothing behind the admin's back",
                "<gray>а</gray> <red>б", Colors.toMiniMessage("<gray>а</gray> &cб"));
        eq("colors: pure MiniMessage is untouched", "<gradient:#7B2FFF:#00E1FF>СН</gradient>",
                Colors.toMiniMessage("<gradient:#7B2FFF:#00E1FF>СН</gradient>"));
        // The brand prefix itself, rewritten by an admin in every notation at once.
        eq("colors: the prefix rewritten in mixed notations",
                "<#7b2fff>SN<#00e1ff>Social<dark_gray>»",
                Colors.toMiniMessage("&#7B2FFFSN&x&0&0&E&1&F&FSocial<dark_gray>»"));

        // Reward titles and lore from config.yml take the same trip (see SNSocialConfig).
        eq("colors: a reward title in HEX becomes a tag", "<reset><#ffaa00>VIP",
                Colors.toMiniMessage("&#FFAA00VIP"));
        eq("colors: a lore line keeps its gradient and gains the code",
                "<gradient:#7B2FFF:#00E1FF>алмазы</gradient> <gray>×3",
                Colors.toMiniMessage("<gradient:#7B2FFF:#00E1FF>алмазы</gradient> &7×3"));

        // An ampersand inside a tag argument belongs to the argument. A query string is the
        // case that makes this matter: "&b=2" turning into <aqua> breaks the link and the tag.
        eq("colors: a query string inside a tag survives",
                "<click:open_url:https://site/?a=1&b=2>ссылка</click>",
                Colors.toMiniMessage("<click:open_url:https://site/?a=1&b=2>ссылка</click>"));
        eq("colors: but a colour code outside one is still a colour code",
                "<click:open_url:https://site/?a=1&b=2><red>ссылка</click>",
                Colors.toMiniMessage("<click:open_url:https://site/?a=1&b=2>&cссылка</click>"));
        // "<5 ...>" is not a tag, so the value counts as markup-free and the code inside it is
        // converted with the legacy reset, exactly as if the brackets were not there.
        eq("colors: a word in brackets is not a tag, so codes inside it still convert",
                "<5 и <reset><green>>", Colors.toMiniMessage("<5 и &a>"));

        // A negation tag is a tag. Item lore is italic by default, so "<!italic>" at the head
        // of a lore line is the idiomatic thing to write - and the reset the legacy branch
        // would emit in front of the next colour would turn the italic straight back on.
        eq("colors: a negation tag counts as markup, so no reset is emitted",
                "<!italic>алмазы <gray>×3", Colors.toMiniMessage("<!italic>алмазы &7×3"));
        eq("colors: and the plain copy drops the negation tag", "алмазы ×3",
                Colors.strip("<!italic>алмазы &7×3"));

        // MiniMessage's escape, and the two sinks agreeing about it.
        eq("colors: an escaped bracket is not a tag", "\\<red>текст",
                Colors.toMiniMessage("\\<red>текст"));
        eq("colors: and the plain copy shows what the player sees", "<red>текст",
                Colors.strip("\\<red>текст"));
        // A backslash right in front of a converted code would otherwise escape the tag the
        // converter just produced, and the player would read "<red>" instead of seeing red.
        eq("colors: a trailing backslash cannot swallow a converted tag",
                "путь\\\\<reset><red>текст", Colors.toMiniMessage("путь\\&cтекст"));

        // The bot path: colours out, words in angle brackets kept.
        eq("colors: codes stripped for bots", "привет мир",
                Colors.strip("&cпривет &#7B2FFFмир"));
        eq("colors: long hex stripped too", "мир", Colors.strip("&x&7&B&2&F&F&Fмир"));
        eq("colors: known tags stripped", "ок", Colors.strip("<green>ок</green>"));
        eq("colors: hex tag stripped", "ок", Colors.strip("<#7B2FFF>ок</#7B2FFF>"));
        eq("colors: a word in brackets survives", "<игрок>", Colors.strip("<игрок>"));
        eq("colors: newline becomes a newline", "а\nб", Colors.strip("а<newline>б"));
        eq("colors: click wrapper stripped, text kept", "Нажми сюда",
                Colors.strip("<click:open_url:'https://t.me/bot'>"
                        + "<hover:show_text:'Открыть'><aqua><u>Нажми сюда</u></aqua>"
                        + "</hover></click>"));
        // Unquoted spaces inside a click argument are how people actually write run_command,
        // so the bound on a tag stops at a second '<' and at nothing else.
        eq("colors: a run_command with spaces is still one tag", "/snsocial link telegram",
                Colors.strip("<click:run_command:/snsocial link telegram>"
                        + "<u><aqua>/snsocial link telegram</aqua></u></click>"));
        // A '<' the admin typed as a character, with a real tag further along the line: the
        // search for the closing bracket has to stop, or the words in between disappear.
        eq("colors: an ordinary bracket keeps the sentence", "цена < 5 рублей",
                Colors.strip("цена < 5 <red>рублей</red>"));
        eq("colors: two brackets in a row are text", "Z<<c:{/{", Colors.strip("Z<<c:{/{&c"));
        eq("colors: and it survives the trip through the converter too", "Z<<c:{/{",
                Colors.strip(Colors.toMiniMessage("Z<<c:{/{§c")));
        eq("colors: a tag whose argument has spaces in quotes is still a tag", "Открыть",
                Colors.strip("<hover:show_text:'Открыть канал'>Открыть</hover>"));
        eq("colors: a quoted argument may hold a tag of its own", "слово",
                Colors.strip("<hover:show_text:'<red>подсказка'>слово</hover>"));
        // A quote opens an argument only right after the ':' that begins one, which is what
        // keeps an apostrophe in the middle of a word from swallowing the rest of the line.
        eq("colors: an apostrophe in a word is not a quoted argument", "<don't>",
                Colors.strip("<don't>"));

        // Both paths must agree on what the reader ends up with: converting the colours and
        // then stripping them has to leave exactly what stripping the original leaves. Random
        // input rather than hand-picked, because the case that found this class's last bug
        // ("Z<<c:{/{§c") is not one anybody would have thought to write down. Fixed seeds, so a
        // failure here is reproducible rather than a story about a build that once went red.
        // The backslash and both quote characters are in the alphabet on purpose: without them
        // the escape branch and the quoted-argument branch of tagEnd have no guard at all.
        String alphabet = "<>&§#!/:{}'\"\\ абвАБ019cfklrx";
        String worst = null;
        int inputs = 0;
        for (long seed : new long[] {20260912L, 1L, 7L, 99L, 4242L}) {
            java.util.Random random = new java.util.Random(seed);
            for (int round = 0; round < 40_000 && worst == null; round++) {
                StringBuilder sample = new StringBuilder();
                int length = 1 + random.nextInt(16);
                for (int i = 0; i < length; i++) {
                    sample.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String raw = sample.toString();
                inputs++;
                if (!Colors.strip(raw).equals(Colors.strip(Colors.toMiniMessage(raw)))) {
                    worst = raw;
                }
            }
        }
        check("colors: stripping agrees before and after conversion, " + inputs + " random inputs"
                + (worst == null ? "" : ", first disagreement: " + worst), worst == null);
    }

    private static void deleteTree(java.nio.file.Path root) throws Exception {
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path path : walk.sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------------- importer

    private static void importer() {
        String fixture = String.join("\n",
                "mysql:",
                "  ip: localhost",
                "  player-indented: not-a-grant",
                "messages:",
                "  reward:",
                "    reward_ok: '&aДержи награду'",
                "player-Somikyy: received",
                "'player-With Space': received",
                "player-Somikyy: received",
                "# player-Commented: received",
                "give-reward:",
                "  commands:",
                "  - lp user %player% parent add vip");
        List<String> nicks = FmSocialRewardImport.parseNicknames(fixture);
        check("import: exactly the top-level player- keys",
                nicks.equals(List.of("Somikyy", "With Space")));
        check("import: empty file = empty list",
                FmSocialRewardImport.parseNicknames("").isEmpty());
    }

    // ----------------------------------------------------------------------- status cache

    private static void statusCache() {
        UUID player = UUID.nameUUIDFromBytes("cache".getBytes(StandardCharsets.UTF_8));
        StatusCache cache = new StatusCache();
        long now = 1_754_000_000_000L;

        cache.put(player, Network.TELEGRAM, SubscriptionStatus.SUBSCRIBED, now);
        check("cache: fresh value returned",
                cache.get(player, Network.TELEGRAM, now + 1000, 60_000)
                        == SubscriptionStatus.SUBSCRIBED);
        check("cache: stale value = UNKNOWN",
                cache.get(player, Network.TELEGRAM, now + 61_000, 60_000)
                        == SubscriptionStatus.UNKNOWN);

        cache.put(player, Network.VK, SubscriptionStatus.UNKNOWN, now);
        check("cache: UNKNOWN is never stored",
                cache.get(player, Network.VK, now + 1, 60_000)
                        == SubscriptionStatus.UNKNOWN);

        cache.invalidate(player);
        check("cache: invalidate drops the player",
                cache.get(player, Network.TELEGRAM, now + 1, 60_000)
                        == SubscriptionStatus.UNKNOWN);
    }

    // -------------------------------------------------------------------------- helpers

    /** Like {@link #check}, but the failure line says what was expected and what arrived. */
    private static void eq(String what, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
        } else {
            FAILURES.add(what + " — expected " + expected + ", got " + actual);
        }
    }

    private interface Failing {
        void run() throws Exception;
    }

    private static boolean throwsIo(Failing action) {
        try {
            action.run();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private CoreSelfTest() {
    }
}
