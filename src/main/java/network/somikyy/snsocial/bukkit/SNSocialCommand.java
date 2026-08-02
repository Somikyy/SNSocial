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
import network.somikyy.snsocial.core.LinkCodeService;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.RewardDef;
import network.somikyy.snsocial.core.SubscriptionStatus;
import network.somikyy.snsocial.core.Version;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /snsocial} - the whole player and admin surface.
 *
 * <p>Every handler that touches storage or the network immediately hops onto the worker;
 * the command thread only parses arguments and answers permission errors. The unlink
 * confirmation is a 30-second in-memory handshake - destructive enough to double-check,
 * not destructive enough for a GUI.
 */
final class SNSocialCommand implements CommandExecutor, TabCompleter {

    private static final long UNLINK_CONFIRM_MILLIS = 30_000;

    private final SNSocialPlugin plugin;
    private final Texts texts;

    /** network → confirmation deadline; keyed per player. */
    private final Map<UUID, Map<Network, Long>> unlinkConfirmations = new HashMap<>();

    SNSocialCommand(SNSocialPlugin plugin, Texts texts) {
        this.plugin = plugin;
        this.texts = texts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label,
                             String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.gui().open(player);
            } else {
                texts.send(sender, "cmd.players-only");
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "link" -> link(sender, args);
            case "unlink" -> unlink(sender, args);
            case "claim" -> claim(sender, args);
            case "status" -> status(sender);
            case "check" -> admin(sender, () -> check(sender, args));
            case "info" -> admin(sender, () -> info(sender, args));
            case "reload" -> admin(sender, () -> plugin.reloadEverything(sender));
            case "import" -> admin(sender, () -> importData(sender, args));
            case "version" -> admin(sender, () -> texts.send(sender, "cmd.version",
                    "version", Version.VERSION));
            default -> texts.send(sender, "cmd.usage");
        }
        return true;
    }

    private void admin(CommandSender sender, Runnable action) {
        if (!sender.hasPermission("snsocial.admin")) {
            texts.send(sender, "cmd.no-permission");
            return;
        }
        action.run();
    }

    // ------------------------------------------------------------------------------- link

    private void link(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            texts.send(sender, "cmd.players-only");
            return;
        }
        Network network = args.length > 1 ? Network.fromId(args[1]) : null;
        if (network == null) {
            texts.send(sender, "link.usage");
            return;
        }
        if (!plugin.networkEnabled(network)) {
            texts.send(sender, "link.disabled",
                    "network", texts.raw("network." + network.id()));
            return;
        }
        plugin.worker().execute(() -> {
            try {
                PlayerLinks links = plugin.storage().links(player.getUniqueId());
                if (links.isLinked(network)) {
                    texts.send(player, "link.already",
                            "network", texts.raw("network." + network.id()));
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "link: чтение привязок не удалось", e);
                texts.send(player, "claim.storage-error");
                return;
            }
            String code = plugin.codes().issue(player.getUniqueId(), player.getName(),
                    network, System.currentTimeMillis());
            if (network == Network.TELEGRAM) {
                String bot = plugin.telegramBotUsername();
                if (bot == null) {
                    texts.send(player, "link.telegram.not-ready");
                    return;
                }
                texts.send(player, "link.telegram.instructions",
                        "code", code, "url", "https://t.me/" + bot + "?start=" + code);
            } else {
                texts.send(player, "link.vk.instructions",
                        "code", code, "url", "https://vk.me/club" + plugin.vkGroupId());
            }
        });
    }

    // ----------------------------------------------------------------------------- unlink

    private void unlink(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            texts.send(sender, "cmd.players-only");
            return;
        }
        Network network = args.length > 1 ? Network.fromId(args[1]) : null;
        if (network == null) {
            texts.send(sender, "unlink.usage");
            return;
        }
        long now = System.currentTimeMillis();
        Long deadline;
        synchronized (unlinkConfirmations) {
            deadline = unlinkConfirmations
                    .getOrDefault(player.getUniqueId(), Map.of()).get(network);
        }
        if (deadline == null || deadline < now) {
            synchronized (unlinkConfirmations) {
                unlinkConfirmations
                        .computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                        .put(network, now + UNLINK_CONFIRM_MILLIS);
            }
            texts.send(player, "unlink.confirm",
                    "network", texts.raw("network." + network.id()));
            return;
        }
        synchronized (unlinkConfirmations) {
            unlinkConfirmations.getOrDefault(player.getUniqueId(), Map.of()).remove(network);
        }
        plugin.worker().execute(() -> {
            try {
                PlayerLinks links = plugin.storage().links(player.getUniqueId());
                if (!links.isLinked(network)) {
                    texts.send(player, "unlink.not-linked",
                            "network", texts.raw("network." + network.id()));
                    return;
                }
                plugin.storage().unlink(player.getUniqueId(), network);
                plugin.cache().invalidate(player.getUniqueId());
                texts.send(player, "unlink.done",
                        "network", texts.raw("network." + network.id()));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "unlink не удался", e);
                texts.send(player, "claim.storage-error");
            }
        });
    }

    // ------------------------------------------------------------------------ claim/status

    private void claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            texts.send(sender, "cmd.players-only");
            return;
        }
        RewardDef def = args.length > 1 ? plugin.service().reward(args[1]) : null;
        if (def == null) {
            texts.send(sender, "claim.unknown-reward");
            return;
        }
        plugin.worker().execute(() -> plugin.service().tryClaim(player, def));
    }

    private void status(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            texts.send(sender, "cmd.players-only");
            return;
        }
        plugin.worker().execute(() -> {
            try {
                PlayerLinks links = plugin.storage().links(player.getUniqueId());
                Map<Network, SubscriptionStatus> statuses =
                        plugin.service().statuses(links, false);
                texts.send(player, "status.header");
                sendStatusLines(player, links, statuses);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "status не удался", e);
                texts.send(player, "claim.storage-error");
            }
        });
    }

    private void sendStatusLines(CommandSender to, PlayerLinks links,
                                 Map<Network, SubscriptionStatus> statuses) {
        for (Network network : Network.values()) {
            if (!plugin.networkEnabled(network)) {
                continue;
            }
            String linked = links.isLinked(network) ? "yes" : "no";
            SubscriptionStatus status =
                    statuses.getOrDefault(network, SubscriptionStatus.UNKNOWN);
            String subscribed = !links.isLinked(network) ? "no"
                    : switch (status) {
                        case SUBSCRIBED -> "yes";
                        case NOT_SUBSCRIBED -> "no";
                        case UNKNOWN -> "unknown";
                    };
            texts.send(to, "status.line",
                    "network", texts.raw("network." + network.id()),
                    "linked", texts.raw("word." + linked),
                    "subscribed", texts.raw("word." + subscribed));
        }
    }

    // ------------------------------------------------------------------------------ admin

    private void check(CommandSender sender, String[] args) {
        UUID target = resolvePlayer(args.length > 1 ? args[1] : null);
        if (target == null) {
            texts.send(sender, "admin.player-not-found");
            return;
        }
        String name = args[1];
        texts.send(sender, "admin.check.started", "player", name);
        plugin.worker().execute(() -> {
            try {
                PlayerLinks links = plugin.storage().links(target);
                if (links.telegramId() == null && links.vkId() == null) {
                    texts.send(sender, "admin.not-linked", "player", name);
                    return;
                }
                plugin.service().recheck(links, name);
                Map<Network, SubscriptionStatus> statuses =
                        plugin.service().statuses(links, false);
                texts.send(sender, "admin.check.done", "player", name);
                sendStatusLines(sender, links, statuses);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "check не удался", e);
                texts.send(sender, "claim.storage-error");
            }
        });
    }

    private void info(CommandSender sender, String[] args) {
        UUID target = resolvePlayer(args.length > 1 ? args[1] : null);
        if (target == null) {
            texts.send(sender, "admin.player-not-found");
            return;
        }
        String name = args[1];
        plugin.worker().execute(() -> {
            try {
                PlayerLinks links = plugin.storage().links(target);
                texts.send(sender, "admin.info.header", "player", name);
                texts.send(sender, "admin.info.telegram", "value",
                        links.telegramId() == null ? texts.raw("word.no")
                                : String.valueOf(links.telegramId()));
                texts.send(sender, "admin.info.vk", "value",
                        links.vkId() == null ? texts.raw("word.no")
                                : String.valueOf(links.vkId()));
                for (ClaimState claim : plugin.storage().claims(target).values()) {
                    if (claim.everClaimed()) {
                        texts.send(sender, "admin.info.claim",
                                "reward", claim.rewardId(),
                                "count", String.valueOf(claim.timesClaimed()),
                                "state", texts.raw(claim.activeClaim()
                                        ? "admin.info.claim-active"
                                        : "admin.info.claim-revoked"));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "info не удался", e);
                texts.send(sender, "claim.storage-error");
            }
        });
    }

    private void importData(CommandSender sender, String[] args) {
        if (args.length < 2 || !"fmsocialreward".equalsIgnoreCase(args[1])) {
            texts.send(sender, "admin.import.usage");
            return;
        }
        String rewardId = args.length > 2 ? args[2] : null;
        plugin.worker().execute(() -> plugin.importFmSocialReward(sender, rewardId));
    }

    /**
     * Name → UUID without ever calling the blocking Mojang lookup. Online first; then the
     * server's own cache; on offline-mode servers the UUID is a deterministic function of
     * the name, so it can simply be computed - which is exactly what the importer needs on
     * the RU servers this plugin is for.
     */
    static UUID resolvePlayer(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached.getUniqueId();
        }
        if (!Bukkit.getOnlineMode()) {
            return UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    // -------------------------------------------------------------------------- completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("link", "unlink", "claim", "status"));
            if (sender.hasPermission("snsocial.admin")) {
                out.addAll(List.of("check", "info", "reload", "import", "version"));
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "link", "unlink" -> out.addAll(List.of("telegram", "vk"));
                case "claim" -> plugin.service().rewards()
                        .forEach(def -> out.add(def.id()));
                case "import" -> out.add("fmsocialreward");
                case "check", "info" -> Bukkit.getOnlinePlayers()
                        .forEach(p -> out.add(p.getName()));
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
