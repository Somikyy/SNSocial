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

import java.util.UUID;

/**
 * The linking flow from "a message arrived" to "the account is linked": redeem the code,
 * write the link, classify the outcome. Lives in core so the whole flow - including the
 * conflict path that guards against alt accounts - runs in the offline self-test.
 *
 * <p>The bukkit layer's pollers feed messages in and translate outcomes into bot replies and
 * in-game notifications; they add no logic of their own.
 */
public final class LinkService {

    /** What happened to an incoming message carrying (maybe) a code. */
    public sealed interface Outcome {

        /** Linked successfully; greet the user and notify the player in game. */
        record Linked(UUID player, String playerName) implements Outcome {
        }

        /** The social account is already linked to a different player. */
        record Conflict(String otherPlayerName) implements Outcome {
        }

        /** The text carried no live code for this network; ignore or hint politely. */
        record BadCode() implements Outcome {
        }

        /** The code was fine but the database write failed; the player should retry. */
        record StorageError(String detail) implements Outcome {
        }
    }

    private final LinkCodeService codes;
    private final Storage storage;

    public LinkService(LinkCodeService codes, Storage storage) {
        this.codes = codes;
        this.storage = storage;
    }

    /**
     * Handles one incoming message from the given network.
     *
     * <p>Code redemption happens before the storage write, and the code is consumed either
     * way: a code that produced a conflict is spent, because leaving it live would let the
     * sender retry against a race they should not win.
     */
    public Outcome tryRedeem(Network network, long socialId, String text, long now) {
        LinkCodeService.Pending pending = codes.redeem(text, network, now);
        if (pending == null) {
            return new Outcome.BadCode();
        }
        try {
            storage.link(pending.player(), pending.playerName(), network, socialId, now);
            return new Outcome.Linked(pending.player(), pending.playerName());
        } catch (Storage.LinkConflict conflict) {
            return new Outcome.Conflict(conflict.otherPlayerName());
        } catch (Exception e) {
            return new Outcome.StorageError(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
