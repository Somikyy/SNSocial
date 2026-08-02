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

import network.somikyy.snsocial.core.ClaimState;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.RewardDef;
import network.somikyy.snsocial.core.RewardEngine;
import network.somikyy.snsocial.core.StatusCache;
import network.somikyy.snsocial.core.Storage;
import network.somikyy.snsocial.core.SubscriptionStatus;
import network.somikyy.snsocial.core.TelegramApi;
import network.somikyy.snsocial.core.VkApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Checks, grants and revokes - the plugin's business side, glued to the server.
 *
 * <p>Every method here runs on the single worker thread, which is what serializes claim
 * mutations: two GUI clicks race in the queue, not in the database. The only two things that
 * hop to a game thread are console-command dispatch ({@code GlobalRegionScheduler} - the
 * Folia-compatible way that works identically on plain Paper) and nothing else; chat messages
 * go straight out, which Paper's Adventure implementation permits from any thread.
 */
final class RewardService {

    /** Fresh-check pacing during bulk re-checks: ~150 ms per player keeps both APIs far
     *  under their limits (VK community token: 20 req/s; Telegram: ~30/s). */
    private static final long RECHECK_PACE_MILLIS = 150;

    /** How stale a cached status may be before the GUI path re-checks it anyway. */
    static final long CACHE_MAX_AGE_MILLIS = 60_000;

    private final Plugin plugin;
    private final Texts texts;
    private final Storage storage;
    private final StatusCache cache;
    private final TelegramApi telegram;
    private final VkApi vk;
    private final String telegramChannel;
    private final java.util.List<RewardDef> rewards;
    private final PlaceholderData placeholders;

    RewardService(Plugin plugin, Texts texts, Storage storage, StatusCache cache,
                  TelegramApi telegram, String telegramChannel, VkApi vk,
                  java.util.List<RewardDef> rewards, PlaceholderData placeholders) {
        this.plugin = plugin;
        this.texts = texts;
        this.storage = storage;
        this.cache = cache;
        this.telegram = telegram;
        this.telegramChannel = telegramChannel;
        this.vk = vk;
        this.rewards = rewards;
        this.placeholders = placeholders;
    }

    java.util.List<RewardDef> rewards() {
        return rewards;
    }

    RewardDef reward(String id) {
        for (RewardDef def : rewards) {
            if (def.id().equalsIgnoreCase(id)) {
                return def;
            }
        }
        return null;
    }

    /** Live check of one network for one linked account; result lands in the cache. */
    SubscriptionStatus freshCheck(UUID player, Network network, long socialId) {
        SubscriptionStatus status;
        if (network == Network.TELEGRAM && telegram != null) {
            status = telegram.checkMember(telegramChannel, socialId);
        } else if (network == Network.VK && vk != null) {
            status = vk.checkMember(socialId);
        } else {
            status = SubscriptionStatus.UNKNOWN;
        }
        cache.put(player, network, status, System.currentTimeMillis());
        return status;
    }

    /**
     * Statuses for the reward engine: cached when fresh, re-checked when stale. Networks the
     * player has not linked stay out of the map - the engine reports them as NEED_LINK from
     * the links object, not from a status.
     */
    Map<Network, SubscriptionStatus> statuses(PlayerLinks links, boolean forceFresh) {
        Map<Network, SubscriptionStatus> out = new EnumMap<>(Network.class);
        long now = System.currentTimeMillis();
        for (Network network : Network.values()) {
            Long socialId = links.idOf(network);
            if (socialId == null) {
                continue;
            }
            SubscriptionStatus cached = forceFresh
                    ? SubscriptionStatus.UNKNOWN
                    : cache.get(links.player(), network, now, CACHE_MAX_AGE_MILLIS);
            out.put(network, cached == SubscriptionStatus.UNKNOWN
                    ? freshCheck(links.player(), network, socialId)
                    : cached);
        }
        return out;
    }

    /**
     * The claim path: fresh statuses (anti-abuse - a claim is the one moment a stale
     * "subscribed" would hand out an unearned reward), then the engine's verdict, then either
     * the grant or the localized reason.
     */
    void tryClaim(Player player, RewardDef reward) {
        try {
            PlayerLinks links = storage.links(player.getUniqueId());
            Map<Network, SubscriptionStatus> statuses = statuses(links, true);
            ClaimState claim = storage.claim(player.getUniqueId(), reward.id());
            RewardEngine.Availability verdict = RewardEngine.availability(
                    reward, links, statuses, claim, System.currentTimeMillis());
            switch (verdict.state()) {
                case AVAILABLE -> grant(player.getUniqueId(), player.getName(), reward, claim);
                case NEED_LINK -> texts.send(player, "claim.need-link",
                        "networks", networkNames(verdict.missing()));
                case NEED_SUBSCRIBE -> texts.send(player, "claim.need-subscribe",
                        "networks", networkNames(verdict.missing()));
                case CHECK_FAILED -> texts.send(player, "claim.check-failed");
                case ALREADY_CLAIMED -> texts.send(player, "claim.already");
                case COOLDOWN -> texts.send(player, "claim.cooldown",
                        "time", texts.duration(verdict.remainingMillis()));
                case LOCKED -> texts.send(player, "claim.locked");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось выдать награду '"
                    + reward.id() + "' игроку " + player.getName(), e);
            texts.send(player, "claim.storage-error");
        }
    }

    /**
     * Re-checks one player: refresh statuses, revoke what a confirmed unsubscribe voids,
     * auto-claim what became available. Used by the join hook, the periodic walk and
     * {@code /snsocial check}.
     */
    void recheck(PlayerLinks links, String knownName) {
        Map<Network, SubscriptionStatus> statuses = statuses(links, true);
        String name = knownName;
        int available = 0;
        try {
            if (name == null) {
                name = storage.playerName(links.player()).orElse(links.player().toString());
            }
            for (RewardDef reward : rewards) {
                ClaimState claim = storage.claim(links.player(), reward.id());
                if (RewardEngine.shouldRevoke(reward, statuses, claim)) {
                    revoke(links.player(), name, reward, claim);
                    continue;
                }
                RewardEngine.Availability verdict = RewardEngine.availability(
                        reward, links, statuses, claim, System.currentTimeMillis());
                if (verdict.state() == RewardEngine.State.AVAILABLE) {
                    if (reward.autoClaim()) {
                        grant(links.player(), name, reward, claim);
                    } else {
                        available++;
                    }
                }
            }
            placeholders.putLinks(links);
            placeholders.putAvailable(links.player(), available);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Перепроверка игрока " + name + " не удалась", e);
        }
    }

    /** The periodic walk over everyone linked, paced to stay far under API limits. */
    void recheckAll() {
        java.util.List<PlayerLinks> everyone;
        try {
            everyone = storage.allLinked();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Перепроверка: не удалось прочитать список привязок", e);
            return;
        }
        for (PlayerLinks links : everyone) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            recheck(links, null);
            try {
                Thread.sleep(RECHECK_PACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        plugin.getLogger().info("Перепроверка подписок завершена: игроков с привязками — "
                + everyone.size() + ".");
    }

    // -------------------------------------------------------------------- grant and revoke

    private void grant(UUID player, String name, RewardDef reward, ClaimState claim)
            throws Exception {
        storage.putClaim(player, claim.afterClaim(System.currentTimeMillis()));
        dispatch(reward.commands(), name, player);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            texts.send(online, "claim.success", "reward", reward.displayName());
        }
        plugin.getLogger().info("Награда '" + reward.id() + "' выдана игроку " + name + ".");
    }

    private void revoke(UUID player, String name, RewardDef reward, ClaimState claim)
            throws Exception {
        storage.putClaim(player, claim.afterRevoke(System.currentTimeMillis()));
        dispatch(reward.revokeCommands(), name, player);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            texts.send(online, "revoke.notify", "reward", reward.displayName());
        }
        plugin.getLogger().info("Награда '" + reward.id() + "' откатана у игрока " + name
                + ": подписка не подтвердилась.");
    }

    /**
     * Console commands run on the global region thread - the Folia-safe equivalent of the
     * main thread, and on plain Paper exactly the main thread. Never from the worker.
     */
    private void dispatch(java.util.List<String> commands, String name, UUID uuid) {
        if (commands.isEmpty()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            for (String template : commands) {
                String command = RewardEngine.expandCommand(template, name, uuid);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Команда награды упала: " + command, e);
                }
            }
        });
    }

    String networkNames(java.util.List<Network> networks) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Network network : networks) {
            joiner.add(texts.raw("network." + network.id()));
        }
        return joiner.toString();
    }
}
