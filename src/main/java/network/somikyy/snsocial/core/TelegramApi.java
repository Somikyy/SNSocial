/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Telegram Bot API client: exactly the four methods SNSocial needs.
 *
 * <p>Facts this class is built on, verified against Bot API 10.2 (July 2026),
 * core.telegram.org/bots/api - see docs/SPEC-SNSocial.md §4.1:
 *
 * <ul>
 *   <li>{@code getChatMember} statuses: subscribed = creator/administrator/member;
 *       not subscribed = left/kicked; restricted (supergroups only) carries {@code is_member}.
 *       The bot must be an administrator of the channel for the call to work at all.</li>
 *   <li>A 400 with "user not found"/"member not found" happens for users the bot has never
 *       seen; in our flow the player messages the bot first, so this reads as not subscribed
 *       rather than as an outage.</li>
 *   <li>{@code getUpdates} long polling confirms processed updates by passing
 *       {@code offset = max(update_id) + 1} on the next call.</li>
 * </ul>
 *
 * <p>All parsing lives in static methods over response strings - that is what the offline
 * self-test exercises. Transport failures surface as {@link SubscriptionStatus#UNKNOWN} (for
 * checks) or as IOException (for the poller, which owns backoff).
 */
public final class TelegramApi {

    /** One private message to the bot, already reduced to what linking needs. */
    public record Update(long updateId, long fromId, long chatId, String text, long dateSeconds) {
    }

    /** A getUpdates batch: messages plus the offset to send next time. */
    public record Updates(long nextOffset, List<Update> messages) {
    }

    private final HttpTransport http;
    private final String base;

    public TelegramApi(HttpTransport http, String botToken) {
        this.http = http;
        this.base = "https://api.telegram.org/bot" + botToken + "/";
    }

    /** Bot username via getMe - needed to build the t.me deep link. Null when the call fails. */
    public String fetchBotUsername() {
        try {
            return parseBotUsername(http.postForm(base + "getMe", Map.of(), 15));
        } catch (IOException | InterruptedException | RuntimeException e) {
            restoreInterrupt(e);
            return null;
        }
    }

    /** Is the user subscribed to the channel. Never throws: failures are UNKNOWN. */
    public SubscriptionStatus checkMember(String chatId, long userId) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("chat_id", chatId);
            form.put("user_id", Long.toString(userId));
            return interpretChatMember(http.postForm(base + "getChatMember", form, 15));
        } catch (IOException | InterruptedException | RuntimeException e) {
            restoreInterrupt(e);
            return SubscriptionStatus.UNKNOWN;
        }
    }

    /**
     * One long-poll iteration. Throws on transport or API errors so the poller can log once
     * and back off, instead of spinning on a dead token at full speed.
     */
    public Updates getUpdates(long offset, int timeoutSeconds)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        if (offset > 0) {
            form.put("offset", Long.toString(offset));
        }
        form.put("timeout", Integer.toString(timeoutSeconds));
        form.put("allowed_updates", "[\"message\"]");
        return parseUpdates(http.postForm(base + "getUpdates", form, timeoutSeconds + 10),
                offset);
    }

    /** Replies to a user in the private chat. Best-effort: a lost reply is not an error. */
    public boolean sendMessage(long chatId, String text) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("chat_id", Long.toString(chatId));
            form.put("text", text);
            Map<String, Object> response =
                    MiniJson.parseObject(http.postForm(base + "sendMessage", form, 15));
            return MiniJson.asBool(response, "ok", false);
        } catch (IOException | InterruptedException | RuntimeException e) {
            restoreInterrupt(e);
            return false;
        }
    }

    // ------------------------------------------------------------ pure parsing (self-tested)

    /**
     * Maps a getChatMember response onto {@link SubscriptionStatus}.
     *
     * <p>The status table is exhaustive over Bot API 10.2's six ChatMember subtypes; an
     * unrecognized status from a future API version degrades to UNKNOWN, which pauses claims
     * but never revokes - the safe direction to be wrong in.
     */
    public static SubscriptionStatus interpretChatMember(String json) {
        Map<String, Object> response;
        try {
            response = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            return SubscriptionStatus.UNKNOWN;
        }
        if (!MiniJson.asBool(response, "ok", false)) {
            String description = MiniJson.str(response, "description");
            String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
            // "user not found" / "member not found": Telegram has never seen this user in
            // the channel or anywhere near the bot. Confirmed absence, not an outage.
            if (d.contains("user not found") || d.contains("member not found")
                    || d.contains("participant_id_invalid")) {
                return SubscriptionStatus.NOT_SUBSCRIBED;
            }
            return SubscriptionStatus.UNKNOWN;
        }
        Map<String, Object> result = MiniJson.obj(response, "result");
        String status = MiniJson.str(result, "status");
        if (status == null) {
            return SubscriptionStatus.UNKNOWN;
        }
        return switch (status) {
            case "creator", "administrator", "member" -> SubscriptionStatus.SUBSCRIBED;
            case "left", "kicked" -> SubscriptionStatus.NOT_SUBSCRIBED;
            case "restricted" -> MiniJson.asBool(result, "is_member", false)
                    ? SubscriptionStatus.SUBSCRIBED
                    : SubscriptionStatus.NOT_SUBSCRIBED;
            default -> SubscriptionStatus.UNKNOWN;
        };
    }

    /**
     * Extracts private text messages from a getUpdates response.
     *
     * <p>The next offset advances over <em>every</em> update in the batch, including ones we
     * skip (edited messages, stickers, group noise): an unconfirmed update would be redelivered
     * forever.
     */
    public static Updates parseUpdates(String json, long currentOffset) throws IOException {
        Map<String, Object> response;
        try {
            response = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            throw new IOException("Telegram returned malformed JSON: " + shorten(json), e);
        }
        if (!MiniJson.asBool(response, "ok", false)) {
            throw new IOException("Telegram getUpdates error: "
                    + MiniJson.str(response, "description"));
        }
        long nextOffset = currentOffset;
        List<Update> messages = new ArrayList<>();
        for (Object item : MiniJson.list(response, "result")) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> update = (Map<String, Object>) item;
            long updateId = MiniJson.asLong(update, "update_id", -1);
            if (updateId >= 0 && updateId + 1 > nextOffset) {
                nextOffset = updateId + 1;
            }
            Map<String, Object> message = MiniJson.obj(update, "message");
            Map<String, Object> from = MiniJson.obj(message, "from");
            Map<String, Object> chat = MiniJson.obj(message, "chat");
            String text = MiniJson.str(message, "text");
            if (from == null || chat == null || text == null) {
                continue;
            }
            long fromId = MiniJson.asLong(from, "id", -1);
            long chatId = MiniJson.asLong(chat, "id", -1);
            if (fromId <= 0) {
                continue;
            }
            messages.add(new Update(updateId, fromId, chatId, text,
                    MiniJson.asLong(message, "date", 0)));
        }
        return new Updates(nextOffset, messages);
    }

    /** Bot username out of a getMe response, or null. */
    public static String parseBotUsername(String json) {
        try {
            Map<String, Object> response = MiniJson.parseObject(json);
            if (!MiniJson.asBool(response, "ok", false)) {
                return null;
            }
            return MiniJson.str(MiniJson.obj(response, "result"), "username");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String shorten(String s) {
        return s == null ? "null" : s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private static void restoreInterrupt(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
