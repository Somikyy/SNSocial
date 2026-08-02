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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * VK API client: membership check plus the Bots Long Poll loop for receiving link codes.
 *
 * <p>Facts this class is built on, verified against dev.vk.com and the official
 * VKCOM/vk-api-schema (August 2026) - see docs/SPEC-SNSocial.md §4.2:
 *
 * <ul>
 *   <li>{@code groups.isMember} accepts a community token (schema:
 *       access_token_type = user/group/service) and returns 0/1, or {@code {member: 0/1}}
 *       in extended form.</li>
 *   <li>Long Poll: {@code groups.getLongPollServer} needs a community key with the
 *       {@code manage} scope; the poll URL is {@code {server}?act=a_check&key&ts&wait};
 *       {@code failed:1} = take the new ts, {@code failed:2/3} = re-request the server.</li>
 *   <li>{@code message_new} (API ≥5.103) carries {@code object.message.from_id} and
 *       {@code object.message.text}.</li>
 *   <li>Community-token rate limit is 20 req/s; error 6 = too many requests.</li>
 * </ul>
 *
 * <p>The base URL is configurable ({@code api.vk.com} / {@code api.vk.ru}): which one is
 * reachable depends on where the server is hosted, and that is the admin's fact, not ours.
 */
public final class VkApi {

    /** VK API version this client is written against. Sent with every request. */
    public static final String API_VERSION = "5.199";

    /** Connection coordinates handed out by groups.getLongPollServer. */
    public record LongPollServer(String server, String key, String ts) {
    }

    /** One incoming group message, reduced to what linking needs. */
    public record VkMessage(long fromId, long peerId, String text) {
    }

    /**
     * One poll iteration. {@code failed} = 0 means ok; 1 means take {@code ts} and continue;
     * 2/3 mean the server must be re-requested via {@link #getLongPollServer()}.
     */
    public record PollResult(String ts, List<VkMessage> messages, int failed) {
    }

    private final HttpTransport http;
    private final String methodBase;
    private final String token;
    private final long groupId;

    public VkApi(HttpTransport http, String apiUrl, String groupToken, long groupId) {
        this.http = http;
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.methodBase = base + "/method/";
        this.token = groupToken;
        this.groupId = groupId;
    }

    /** Is the user a member of the group. Never throws: failures are UNKNOWN. */
    public SubscriptionStatus checkMember(long userId) {
        try {
            Map<String, String> form = baseParams();
            form.put("group_id", Long.toString(groupId));
            form.put("user_id", Long.toString(userId));
            return interpretIsMember(
                    http.postForm(methodBase + "groups.isMember", form, 15));
        } catch (IOException | InterruptedException | RuntimeException e) {
            restoreInterrupt(e);
            return SubscriptionStatus.UNKNOWN;
        }
    }

    /** Long-poll coordinates. Throws so the poller owns logging and backoff. */
    public LongPollServer getLongPollServer() throws IOException, InterruptedException {
        Map<String, String> form = baseParams();
        form.put("group_id", Long.toString(groupId));
        return parseLongPollServer(
                http.postForm(methodBase + "groups.getLongPollServer", form, 15));
    }

    /** One wait-and-fetch cycle against the long-poll server. */
    public PollResult poll(LongPollServer server, String ts, int waitSeconds)
            throws IOException, InterruptedException {
        String url = server.server()
                + "?act=a_check&key=" + URLEncoder.encode(server.key(), StandardCharsets.UTF_8)
                + "&ts=" + URLEncoder.encode(ts, StandardCharsets.UTF_8)
                + "&wait=" + waitSeconds;
        return parsePoll(http.get(url, waitSeconds + 10));
    }

    /** Replies to the user in the group's messages. Best-effort. */
    public boolean sendMessage(long peerId, String text) {
        try {
            Map<String, String> form = baseParams();
            form.put("peer_id", Long.toString(peerId));
            form.put("message", text);
            // random_id deduplicates sends and is required for community messages.
            form.put("random_id", Long.toString(ThreadLocalRandom.current().nextLong()));
            Map<String, Object> response = MiniJson.parseObject(
                    http.postForm(methodBase + "messages.send", form, 15));
            return response.containsKey("response");
        } catch (IOException | InterruptedException | RuntimeException e) {
            restoreInterrupt(e);
            return false;
        }
    }

    private Map<String, String> baseParams() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("access_token", token);
        form.put("v", API_VERSION);
        return form;
    }

    // ------------------------------------------------------------ pure parsing (self-tested)

    /** Maps a groups.isMember response onto {@link SubscriptionStatus}. */
    public static SubscriptionStatus interpretIsMember(String json) {
        Map<String, Object> response;
        try {
            response = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            return SubscriptionStatus.UNKNOWN;
        }
        if (response.containsKey("error")) {
            // Includes error 6 (rate limit) and permission errors: all UNKNOWN - the check
            // failed, nothing was established about the player.
            return SubscriptionStatus.UNKNOWN;
        }
        Object result = response.get("response");
        if (result instanceof Long plain) {
            return plain == 1 ? SubscriptionStatus.SUBSCRIBED : SubscriptionStatus.NOT_SUBSCRIBED;
        }
        if (result instanceof Map) {
            Map<String, Object> extended = MiniJson.obj(response, "response");
            long member = MiniJson.asLong(extended, "member", -1);
            if (member == 0 || member == 1) {
                return member == 1 ? SubscriptionStatus.SUBSCRIBED
                                   : SubscriptionStatus.NOT_SUBSCRIBED;
            }
        }
        return SubscriptionStatus.UNKNOWN;
    }

    /** Reads groups.getLongPollServer; throws with VK's own error text when it failed. */
    public static LongPollServer parseLongPollServer(String json) throws IOException {
        Map<String, Object> response;
        try {
            response = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            throw new IOException("VK returned malformed JSON", e);
        }
        Map<String, Object> error = MiniJson.obj(response, "error");
        if (error != null) {
            throw new IOException("VK getLongPollServer error "
                    + MiniJson.asLong(error, "error_code", -1) + ": "
                    + MiniJson.str(error, "error_msg"));
        }
        Map<String, Object> result = MiniJson.obj(response, "response");
        String server = MiniJson.str(result, "server");
        String key = MiniJson.str(result, "key");
        String ts = tsAsString(result);
        if (server == null || key == null || ts == null) {
            throw new IOException("VK getLongPollServer: incomplete response");
        }
        return new LongPollServer(server, key, ts);
    }

    /** Reads one long-poll response, including the documented failed:1/2/3 protocol. */
    public static PollResult parsePoll(String json) throws IOException {
        Map<String, Object> response;
        try {
            response = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            throw new IOException("VK long poll returned malformed JSON", e);
        }
        long failed = MiniJson.asLong(response, "failed", 0);
        if (failed != 0) {
            return new PollResult(tsAsString(response), List.of(), (int) failed);
        }
        List<VkMessage> messages = new ArrayList<>();
        for (Object item : MiniJson.list(response, "updates")) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> update = (Map<String, Object>) item;
            if (!"message_new".equals(MiniJson.str(update, "type"))) {
                continue;
            }
            Map<String, Object> message =
                    MiniJson.obj(MiniJson.obj(update, "object"), "message");
            if (message == null) {
                continue;
            }
            long fromId = MiniJson.asLong(message, "from_id", 0);
            // Negative from_id is a community speaking; only humans link accounts.
            if (fromId <= 0) {
                continue;
            }
            messages.add(new VkMessage(fromId,
                    MiniJson.asLong(message, "peer_id", fromId),
                    MiniJson.str(message, "text") == null ? "" : MiniJson.str(message, "text")));
        }
        String ts = tsAsString(response);
        if (ts == null) {
            throw new IOException("VK long poll: response without ts");
        }
        return new PollResult(ts, messages, 0);
    }

    /** ts arrives as a string in long poll but as a number in some error paths; accept both. */
    private static String tsAsString(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Object ts = map.get("ts");
        if (ts instanceof String s) {
            return s;
        }
        if (ts instanceof Long l) {
            return Long.toString(l);
        }
        return null;
    }

    private static void restoreInterrupt(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
