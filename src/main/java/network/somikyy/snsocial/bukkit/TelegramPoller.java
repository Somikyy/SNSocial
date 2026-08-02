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
import network.somikyy.snsocial.core.TelegramApi;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The getUpdates long-poll loop, on its own daemon thread.
 *
 * <p>A dedicated thread rather than a scheduler task because each iteration legitimately
 * blocks for up to 25 seconds - fine for a thread that owns nothing, hostile inside any
 * shared pool. Shutdown is an interrupt: HttpClient's blocking send unblocks on it.
 *
 * <p>Messages older than the link-code TTL are ignored without a reply: after a restart the
 * backlog replays, and answering somebody's week-old "привет" with instructions reads as
 * spam from a haunted bot.
 */
final class TelegramPoller implements Runnable {

    private final Logger logger;
    private final Texts texts;
    private final TelegramApi api;
    private final LinkService linkService;
    private final long codeTtlMillis;
    /** Called with (player uuid, player name) after a successful link, off the game thread. */
    private final BiConsumer<java.util.UUID, String> onLinked;

    private volatile boolean running = true;
    private String lastError;

    TelegramPoller(Logger logger, Texts texts, TelegramApi api, LinkService linkService,
                   long codeTtlMillis, BiConsumer<java.util.UUID, String> onLinked) {
        this.logger = logger;
        this.texts = texts;
        this.api = api;
        this.linkService = linkService;
        this.codeTtlMillis = codeTtlMillis;
        this.onLinked = onLinked;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        long offset = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                TelegramApi.Updates batch = api.getUpdates(offset, 25);
                offset = batch.nextOffset();
                lastError = null;
                long now = System.currentTimeMillis();
                for (TelegramApi.Update update : batch.messages()) {
                    if (update.dateSeconds() * 1000L < now - codeTtlMillis) {
                        continue;
                    }
                    handle(update, now);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // Log each distinct failure once, then go quiet: a dead token during a long
                // night must not produce four thousand identical lines.
                String message = String.valueOf(e.getMessage());
                if (!message.equals(lastError)) {
                    lastError = message;
                    logger.warning("Telegram недоступен, повторяю каждые 10 секунд: "
                            + message);
                }
                if (!sleepQuietly(10_000)) {
                    return;
                }
            }
        }
    }

    private void handle(TelegramApi.Update update, long now) {
        LinkService.Outcome outcome =
                linkService.tryRedeem(Network.TELEGRAM, update.fromId(), update.text(), now);
        if (outcome instanceof LinkService.Outcome.Linked linked) {
            api.sendMessage(update.chatId(),
                    texts.raw("bot.linked", "player", linked.playerName()));
            onLinked.accept(linked.player(), linked.playerName());
        } else if (outcome instanceof LinkService.Outcome.Conflict conflict) {
            api.sendMessage(update.chatId(),
                    texts.raw("bot.conflict", "player", conflict.otherPlayerName()));
        } else if (outcome instanceof LinkService.Outcome.StorageError) {
            api.sendMessage(update.chatId(), texts.raw("bot.storage-error"));
        } else {
            api.sendMessage(update.chatId(), texts.raw("bot.bad-code"));
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
