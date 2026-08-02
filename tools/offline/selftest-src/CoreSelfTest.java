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

    private static void messages() {
        Messages messages = Messages.bundled();
        check("messages: bundles loaded", !messages.keys().isEmpty());
        boolean parity = true;
        for (String key : messages.keys()) {
            if (!messages.has(key, true) || !messages.has(key, false)) {
                parity = false;
                break;
            }
        }
        check("messages: every key exists in BOTH ru and en", parity);
        check("messages: ru is really russian",
                messages.get("word.yes", true).equals("да"));
        check("messages: placeholders fill by name",
                messages.get("bot.linked", true, "player", "Somikyy").contains("Somikyy"));
        check("messages: missing key degrades to the key itself",
                messages.get("no.such.key", true).equals("no.such.key"));
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
