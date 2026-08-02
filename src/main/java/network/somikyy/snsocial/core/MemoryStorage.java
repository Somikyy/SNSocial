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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link Storage} for the offline self-test.
 *
 * <p>This is not a mock - it implements the full contract, uniqueness checks included, so
 * the link/claim scenarios in the self-test exercise exactly the logic the SQL implementation
 * must reproduce. The JDK ships no JDBC driver, so SQL itself is exercised on a real server
 * (the manual scenario in the spec), while everything above the SQL line is proven here.
 */
public final class MemoryStorage implements Storage {

    private record PlayerRow(String name, Long telegramId, Long vkId) {
    }

    private final Map<UUID, PlayerRow> players = new HashMap<>();
    private final Map<UUID, Map<String, ClaimState>> claims = new HashMap<>();

    @Override
    public synchronized PlayerLinks links(UUID player) {
        PlayerRow row = players.get(player);
        return row == null
                ? PlayerLinks.none(player)
                : new PlayerLinks(player, row.telegramId(), row.vkId());
    }

    @Override
    public synchronized void link(UUID player, String playerName, Network network,
                                  long socialId, long now) throws LinkConflict {
        for (Map.Entry<UUID, PlayerRow> e : players.entrySet()) {
            if (e.getKey().equals(player)) {
                continue;
            }
            Long other = network == Network.TELEGRAM ? e.getValue().telegramId()
                                                     : e.getValue().vkId();
            if (other != null && other == socialId) {
                throw new LinkConflict(e.getValue().name() != null
                        ? e.getValue().name() : e.getKey().toString());
            }
        }
        PlayerRow row = players.getOrDefault(player, new PlayerRow(playerName, null, null));
        players.put(player, network == Network.TELEGRAM
                ? new PlayerRow(playerName, socialId, row.vkId())
                : new PlayerRow(playerName, row.telegramId(), socialId));
    }

    @Override
    public synchronized void unlink(UUID player, Network network) {
        PlayerRow row = players.get(player);
        if (row == null) {
            return;
        }
        players.put(player, network == Network.TELEGRAM
                ? new PlayerRow(row.name(), null, row.vkId())
                : new PlayerRow(row.name(), row.telegramId(), null));
    }

    @Override
    public synchronized Optional<UUID> playerBySocialId(Network network, long socialId) {
        for (Map.Entry<UUID, PlayerRow> e : players.entrySet()) {
            Long id = network == Network.TELEGRAM ? e.getValue().telegramId()
                                                  : e.getValue().vkId();
            if (id != null && id == socialId) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized ClaimState claim(UUID player, String rewardId) {
        return claims.getOrDefault(player, Map.of())
                .getOrDefault(rewardId, ClaimState.fresh(rewardId));
    }

    @Override
    public synchronized Map<String, ClaimState> claims(UUID player) {
        return new LinkedHashMap<>(claims.getOrDefault(player, Map.of()));
    }

    @Override
    public synchronized void putClaim(UUID player, ClaimState state) {
        claims.computeIfAbsent(player, k -> new LinkedHashMap<>()).put(state.rewardId(), state);
    }

    @Override
    public synchronized List<PlayerLinks> allLinked() {
        List<PlayerLinks> out = new ArrayList<>();
        for (Map.Entry<UUID, PlayerRow> e : players.entrySet()) {
            if (e.getValue().telegramId() != null || e.getValue().vkId() != null) {
                out.add(new PlayerLinks(e.getKey(), e.getValue().telegramId(),
                        e.getValue().vkId()));
            }
        }
        return out;
    }

    @Override
    public synchronized Optional<String> playerName(UUID player) {
        PlayerRow row = players.get(player);
        return row == null ? Optional.empty() : Optional.ofNullable(row.name());
    }

    @Override
    public void close() {
    }
}
