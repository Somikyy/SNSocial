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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract. Implementations: SQL (SQLite/MySQL) for the server, in-memory for
 * the offline self-test.
 *
 * <p>Methods are synchronous and thread-safe; the bukkit layer calls them from its async
 * executor, never from the main thread. Keeping the interface synchronous keeps every
 * implementation trivial to reason about - asynchrony is the caller's concern, exactly once.
 *
 * <p>One social account maps to at most one player per network ({@link LinkConflict}): ten
 * alts claiming rewards off one Telegram subscription is the first abuse anyone tries.
 */
public interface Storage extends AutoCloseable {

    /** Thrown when the social account is already linked to a different player. */
    final class LinkConflict extends Exception {
        private static final long serialVersionUID = 1L;

        private final String otherPlayerName;

        public LinkConflict(String otherPlayerName) {
            super("account already linked to " + otherPlayerName);
            this.otherPlayerName = otherPlayerName;
        }

        /** Name of the player holding the link; may be a UUID string when the name is gone. */
        public String otherPlayerName() {
            return otherPlayerName;
        }
    }

    /** Links for one player; a fresh {@link PlayerLinks#none} when the player is unknown. */
    PlayerLinks links(UUID player) throws Exception;

    /**
     * Records a link, enforcing account uniqueness per network. Also remembers the player
     * name for admin commands and conflict messages.
     */
    void link(UUID player, String playerName, Network network, long socialId, long now)
            throws LinkConflict, Exception;

    /** Removes a link. Claim history stays - unlink+relink must not reset anti-abuse state. */
    void unlink(UUID player, Network network) throws Exception;

    /** Player currently holding this social account, if any. */
    Optional<UUID> playerBySocialId(Network network, long socialId) throws Exception;

    /** Claim state for one player × reward; {@link ClaimState#fresh} when never touched. */
    ClaimState claim(UUID player, String rewardId) throws Exception;

    /** All claim states for one player, keyed by reward id. */
    Map<String, ClaimState> claims(UUID player) throws Exception;

    /** Persists a claim state (insert or overwrite). */
    void putClaim(UUID player, ClaimState state) throws Exception;

    /**
     * Everyone with at least one linked account - the population the periodic re-check
     * walks. Bounded by the player base, read in one query, iterated off the main thread.
     */
    List<PlayerLinks> allLinked() throws Exception;

    /** Last known player name, for messages about offline players. */
    Optional<String> playerName(UUID player) throws Exception;

    /** Narrowed from AutoCloseable: closing must never be the thing that throws. */
    @Override
    void close();
}
