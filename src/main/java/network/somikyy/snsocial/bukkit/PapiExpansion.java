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

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.SubscriptionStatus;
import network.somikyy.snsocial.core.Version;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI expansion: {@code %snsocial_telegram_linked%}, {@code %snsocial_vk_linked%},
 * {@code %snsocial_telegram_subscribed%}, {@code %snsocial_vk_subscribed%},
 * {@code %snsocial_available%}.
 *
 * <p>This class is only ever loaded when PlaceholderAPI is present - the plugin checks the
 * plugin manager before touching it, so the {@code me.clip} import cannot NoClassDefFoundError
 * on servers without PAPI.
 *
 * <p>Answers come exclusively from {@link PlaceholderData} and {@link
 * network.somikyy.snsocial.core.StatusCache} - never storage, never the network: scoreboards
 * call this on the main thread at frame rate.
 */
final class PapiExpansion extends PlaceholderExpansion {

    private final SNSocialPlugin plugin;
    private final Texts texts;

    PapiExpansion(SNSocialPlugin plugin, Texts texts) {
        this.plugin = plugin;
        this.texts = texts;
    }

    @Override
    public String getIdentifier() {
        return "snsocial";
    }

    @Override
    public String getAuthor() {
        return "Somikyy";
    }

    @Override
    public String getVersion() {
        return Version.VERSION;
    }

    @Override
    public boolean persist() {
        // Survives PlaceholderAPI reloads; we unregister ourselves on plugin disable.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        if ("available".equals(p)) {
            return String.valueOf(plugin.placeholders().available(player.getUniqueId()));
        }
        Network network = p.startsWith("telegram_") ? Network.TELEGRAM
                : p.startsWith("vk_") ? Network.VK : null;
        if (network == null) {
            return null;
        }
        PlayerLinks links = plugin.placeholders().links(player.getUniqueId());
        boolean linked = links != null && links.isLinked(network);
        if (p.endsWith("_linked")) {
            return texts.raw(linked ? "word.yes" : "word.no");
        }
        if (p.endsWith("_subscribed")) {
            if (!linked) {
                return texts.raw("word.no");
            }
            SubscriptionStatus status = plugin.cache().get(player.getUniqueId(), network,
                    System.currentTimeMillis(), Long.MAX_VALUE);
            return texts.raw(switch (status) {
                case SUBSCRIBED -> "word.yes";
                case NOT_SUBSCRIBED -> "word.no";
                case UNKNOWN -> "word.unknown";
            });
        }
        return null;
    }
}
