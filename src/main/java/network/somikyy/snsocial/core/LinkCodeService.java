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

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived codes that tie a chat message to a player.
 *
 * <p>The player asks in game, gets a code, sends it to the bot; whoever sent it from Telegram
 * or VK is the owner of that code's player. Codes live in memory only - a restart voids them,
 * which is fine: issuing a new one is a single command, and codes on disk would be one more
 * thing to leak.
 *
 * <p>The alphabet drops 0/O/1/I/L: players retype these by hand, on phones, between two apps.
 * 6 chars over 31 symbols is ~887M combinations for a code that lives minutes and is issued
 * per-player - guessing is not the attack surface here; mistyping is.
 *
 * <p>Thread-safety: called from the command thread and from the two poller threads, so every
 * public method synchronizes on the instance. Contention is zero in practice - a handful of
 * pending codes at worst.
 */
public final class LinkCodeService {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 6;

    /** A pending code: who asked, for which network, and until when it is valid. */
    public record Pending(UUID player, String playerName, Network network, long expiresAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> byCode = new HashMap<>();
    private final long ttlMillis;

    public LinkCodeService(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /**
     * Issues a code for the player and network, replacing that player's previous pending code
     * for the same network - only the latest code a player sees may work, anything else
     * confuses people who ask twice.
     */
    public synchronized String issue(UUID player, String playerName, Network network, long now) {
        byCode.values().removeIf(p -> p.player().equals(player) && p.network() == network);
        sweep(now);
        String code = randomCode();
        while (byCode.containsKey(code)) {
            code = randomCode();
        }
        byCode.put(code, new Pending(player, playerName, network, now + ttlMillis));
        return code;
    }

    /**
     * Tries to redeem a code arriving from the given network. Case- and whitespace-tolerant:
     * the code travels through two chat apps and at least one keyboard. Returns null when the
     * text is not a live code for that network. A successful redeem consumes the code.
     */
    public synchronized Pending redeem(String text, Network network, long now) {
        if (text == null) {
            return null;
        }
        // Telegram users habitually send "/start CODE"; accept the last token.
        String[] tokens = text.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        String candidate = tokens[tokens.length - 1];
        Pending pending = byCode.get(candidate);
        if (pending == null || pending.network() != network) {
            return null;
        }
        if (pending.expiresAt() < now) {
            byCode.remove(candidate);
            return null;
        }
        byCode.remove(candidate);
        return pending;
    }

    /** Whether the player currently has a live code for the network (for friendlier UX). */
    public synchronized boolean hasPending(UUID player, Network network, long now) {
        sweep(now);
        return byCode.values().stream()
                .anyMatch(p -> p.player().equals(player) && p.network() == network);
    }

    private void sweep(long now) {
        Iterator<Pending> it = byCode.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt() < now) {
                it.remove();
            }
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
