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

import network.somikyy.snsocial.core.PlayerLinks;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The join hook: a delayed off-thread re-check of the joining player.
 *
 * <p>The delay (config {@code check.join-delay-seconds}) keeps the login moment quiet - the
 * player is loading chunks and the server is running login events; our HTTPS round-trips
 * have no business in that second. The check itself revokes what an unsubscribe voided and
 * auto-claims what became available, so a player who subscribed while offline gets greeted
 * with the reward, which is the moment that sells the subscription to the next player.
 */
final class JoinListener implements Listener {

    private final SNSocialPlugin plugin;
    private final ScheduledExecutorService worker;
    private final int delaySeconds;

    JoinListener(SNSocialPlugin plugin, ScheduledExecutorService worker, int delaySeconds) {
        this.plugin = plugin;
        this.worker = worker;
        this.delaySeconds = delaySeconds;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        worker.schedule(() -> {
            try {
                PlayerLinks links = plugin.storage().links(uuid);
                plugin.placeholders().putLinks(links);
                if (links.telegramId() != null || links.vkId() != null) {
                    plugin.service().recheck(links, name);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Проверка при входе не удалась: " + name, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
