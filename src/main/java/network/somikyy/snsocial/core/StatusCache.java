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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Last known subscription statuses, in memory.
 *
 * <p>Exists so the GUI opens instantly from cache while checks run in the background, and so
 * placeholders never trigger network calls - a scoreboard asking for
 * {@code %snsocial_telegram_subscribed%} twenty times a second must cost nothing.
 *
 * <p>Only confirmed results are stored: an UNKNOWN check neither overwrites the previous
 * knowledge nor becomes knowledge itself.
 */
public final class StatusCache {

    /** A confirmed status and when it was established. */
    public record Entry(SubscriptionStatus status, long atMillis) {
    }

    private final Map<UUID, Map<Network, Entry>> byPlayer = new HashMap<>();

    /** Records a check result; UNKNOWN is deliberately not recorded. */
    public synchronized void put(UUID player, Network network, SubscriptionStatus status,
                                 long now) {
        if (status == SubscriptionStatus.UNKNOWN) {
            return;
        }
        byPlayer.computeIfAbsent(player, k -> new EnumMap<>(Network.class))
                .put(network, new Entry(status, now));
    }

    /** Cached status, or UNKNOWN when nothing (or nothing fresh enough) is known. */
    public synchronized SubscriptionStatus get(UUID player, Network network, long now,
                                               long maxAgeMillis) {
        Entry entry = byPlayer.getOrDefault(player, Map.of()).get(network);
        if (entry == null || now - entry.atMillis() > maxAgeMillis) {
            return SubscriptionStatus.UNKNOWN;
        }
        return entry.status();
    }

    /** Cached statuses for all networks at once, for the reward engine's input map. */
    public synchronized Map<Network, SubscriptionStatus> snapshot(UUID player, long now,
                                                                  long maxAgeMillis) {
        Map<Network, SubscriptionStatus> out = new EnumMap<>(Network.class);
        for (Network n : Network.values()) {
            out.put(n, get(player, n, now, maxAgeMillis));
        }
        return out;
    }

    /** Drops one player, e.g. after unlink - stale truth is worse than no truth. */
    public synchronized void invalidate(UUID player) {
        byPlayer.remove(player);
    }
}
