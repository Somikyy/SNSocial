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

    /** Re-log the same error this often: a flapping conflict resets the dedup constantly. */
    private static final long ERROR_LOG_INTERVAL_MILLIS = 5 * 60_000;

    private volatile boolean running = true;
    private String lastError;
    private long lastErrorLoggedAt;
    /** Positive feedback: silence after an error reads as "still broken"; say when it works.
     *  Rate-limited like the errors - a flapping token conflict alternates success and 409
     *  every few seconds, and an honest "восстановлена" each time is just spam in green. */
    private boolean failing;
    private long lastRestoreLoggedAt;

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
                // lastError deliberately NOT cleared here: with two consumers on one token,
                // success and 409 alternate, and clearing on success would re-arm the log
                // for every flap. The 5-minute reminder covers the recovered-then-broke case.
                long now = System.currentTimeMillis();
                if (failing) {
                    failing = false;
                    if (now - lastRestoreLoggedAt > ERROR_LOG_INTERVAL_MILLIS) {
                        lastRestoreLoggedAt = now;
                        logger.info("Telegram: связь восстановлена, бот принимает сообщения.");
                    }
                }
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
                failing = true;
                // Log each distinct failure once (with a periodic reminder), then go quiet:
                // a dead token during a long night must not produce four thousand identical
                // lines. The periodic part matters for the flapping case below, where
                // successes keep resetting the dedup.
                String message = String.valueOf(e.getMessage());
                long now = System.currentTimeMillis();
                if (!message.equals(lastError)
                        || now - lastErrorLoggedAt > ERROR_LOG_INTERVAL_MILLIS) {
                    lastError = message;
                    lastErrorLoggedAt = now;
                    if (message.contains("Conflict")) {
                        // 409 Conflict: Telegram allows exactly ONE getUpdates consumer per
                        // token. Seen in the wild on the very first live test: SNSocial and
                        // the SNTelegram bridge sharing one bot steal each other's updates,
                        // and link codes arrive "every other time". Generic "недоступен"
                        // here costs the admin an evening; the real cause costs one line.
                        logger.warning("Telegram отвечает 409 Conflict: этим токеном уже "
                                + "пользуется другой процесс — другой плагин (например, мост "
                                + "SNTelegram) или второй сервер. У каждого плагина должен "
                                + "быть СВОЙ бот: создай второго бота у @BotFather, впиши его "
                                + "токен в config.yml SNSocial и сделай его админом канала. "
                                + "Пока токен общий, коды привязки будут теряться.");
                    } else {
                        logger.warning("Telegram недоступен, повторяю каждые 10 секунд: "
                                + message);
                    }
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
