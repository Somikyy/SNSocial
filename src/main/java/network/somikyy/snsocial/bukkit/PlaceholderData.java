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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What placeholders are allowed to read.
 *
 * <p>PlaceholderAPI calls arrive on the main thread, often dozens of times per second from
 * scoreboards - they may touch neither storage nor the network, ever. So the worker deposits
 * snapshots here whenever it happens to know something (join check, GUI open, re-check), and
 * placeholders read these maps and nothing else. Slightly stale by design; never blocking.
 */
final class PlaceholderData {

    private final Map<UUID, PlayerLinks> links = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> availableRewards = new ConcurrentHashMap<>();

    void putLinks(PlayerLinks value) {
        links.put(value.player(), value);
    }

    void putAvailable(UUID player, int count) {
        availableRewards.put(player, count);
    }

    PlayerLinks links(UUID player) {
        return links.get(player);
    }

    int available(UUID player) {
        return availableRewards.getOrDefault(player, 0);
    }
}
