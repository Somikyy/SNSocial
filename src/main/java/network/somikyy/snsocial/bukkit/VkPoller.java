/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.bukkit;

import network.somikyy.snsocial.core.LinkService;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.VkApi;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The VK Bots Long Poll loop, on its own daemon thread.
 *
 * <p>Implements the documented failure protocol (SPEC §4.2): {@code failed:1} - keep going
 * with the new ts; {@code failed:2/3} - the key or ts is void, re-request the server via
 * {@code groups.getLongPollServer}. Everything else mirrors {@link TelegramPoller}, including
 * one-line-per-distinct-error logging.
 */
final class VkPoller implements Runnable {

    private final Logger logger;
    private final Texts texts;
    private final VkApi api;
    private final LinkService linkService;
    /** Called with (player uuid, player name) after a successful link, off the game thread. */
    private final BiConsumer<java.util.UUID, String> onLinked;

    /** Re-log the same error this often, so a misconfig noticed at night is still explained
     *  in the morning's console without producing a thousand identical lines in between. */
    private static final long ERROR_LOG_INTERVAL_MILLIS = 5 * 60_000;

    private volatile boolean running = true;
    private String lastError;
    private long lastErrorLoggedAt;
    /** Positive feedback: silence after an error reads as "still broken"; say when it works.
     *  Rate-limited like the errors, so a flapping connection does not spam in green. */
    private boolean everConnected;
    private boolean failing;
    private long lastRestoreLoggedAt;

    VkPoller(Logger logger, Texts texts, VkApi api, LinkService linkService,
             BiConsumer<java.util.UUID, String> onLinked) {
        this.logger = logger;
        this.texts = texts;
        this.api = api;
        this.linkService = linkService;
        this.onLinked = onLinked;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        VkApi.LongPollServer server = null;
        String ts = null;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (server == null) {
                    server = api.getLongPollServer();
                    ts = server.ts();
                    if (!everConnected || failing) {
                        long now = System.currentTimeMillis();
                        if (!everConnected
                                || now - lastRestoreLoggedAt > ERROR_LOG_INTERVAL_MILLIS) {
                            lastRestoreLoggedAt = now;
                            logger.info("VK: Long Poll подключён, сообщения сообщества "
                                    + "принимаются.");
                        }
                        everConnected = true;
                        failing = false;
                    }
                }
                VkApi.PollResult result = api.poll(server, ts, 25);
                if (result.failed() == 1) {
                    // ts fell behind; the response carries the one to continue from.
                    ts = result.ts() != null ? result.ts() : ts;
                    continue;
                }
                if (result.failed() >= 2) {
                    // Key or ts is void; a broken server that answers failed:2 instantly
                    // must not turn this loop into an HTTP flood - breathe before retrying.
                    server = null;
                    if (!sleepQuietly(1_000)) {
                        return;
                    }
                    continue;
                }
                ts = result.ts();
                long now = System.currentTimeMillis();
                for (VkApi.VkMessage message : result.messages()) {
                    handle(message, now);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                failing = true;
                String message = String.valueOf(e.getMessage());
                long now = System.currentTimeMillis();
                if (!message.equals(lastError)
                        || now - lastErrorLoggedAt > ERROR_LOG_INTERVAL_MILLIS) {
                    lastError = message;
                    lastErrorLoggedAt = now;
                    logger.warning(explain(message));
                }
                server = null;
                if (!sleepQuietly(10_000)) {
                    return;
                }
            }
        }
    }

    /**
     * VK's error texts are accurate but English and API-speak; the two that every admin
     * hits during setup deserve a Russian sentence with the exact settings path. Both
     * observed on the very first live install (02.08.2026).
     */
    private static String explain(String message) {
        if (message.contains("longpoll for this group is not enabled")) {
            return "VK: для группы из vk.group-id не включён Long Poll — плагин не может "
                    + "получать сообщения, коды привязки не доходят. Проверь три вещи: "
                    + "1) включена именно вкладка «Long Poll API» (не Callback API!) с "
                    + "версией 5.199 и событием «Входящее сообщение»; 2) vk.group-id — "
                    + "числовой ID ИМЕННО этого сообщества (vk.com/club<ID>); 3) ключ создан "
                    + "в этом же сообществе. Плагин переподключится сам и напишет "
                    + "«Long Poll подключён», когда всё сойдётся.";
        }
        if (message.contains("Access denied") || message.contains("error 27")) {
            return "VK: ключу доступа не хватает прав. Пересоздай ключ сообщества с правами "
                    + "«управление сообществом» (нужно для Long Poll) и «сообщения сообщества» "
                    + "(чтобы бот отвечал) и впиши его в vk.group-token. Ошибка VK: " + message;
        }
        return "VK недоступен, повторяю каждые 10 секунд: " + message;
    }

    private void handle(VkApi.VkMessage message, long now) {
        LinkService.Outcome outcome =
                linkService.tryRedeem(Network.VK, message.fromId(), message.text(), now);
        if (outcome instanceof LinkService.Outcome.Linked linked) {
            api.sendMessage(message.peerId(),
                    texts.raw("bot.linked", "player", linked.playerName()));
            onLinked.accept(linked.player(), linked.playerName());
        } else if (outcome instanceof LinkService.Outcome.Conflict conflict) {
            api.sendMessage(message.peerId(),
                    texts.raw("bot.conflict", "player", conflict.otherPlayerName()));
        } else if (outcome instanceof LinkService.Outcome.StorageError) {
            api.sendMessage(message.peerId(), texts.raw("bot.storage-error"));
        } else {
            api.sendMessage(message.peerId(), texts.raw("bot.bad-code"));
        }
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
