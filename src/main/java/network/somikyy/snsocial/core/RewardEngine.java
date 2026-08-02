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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The decision logic: may this player claim this reward right now, and must anything be
 * rolled back. Pure functions over immutable inputs - no clock of its own, no storage, no
 * Bukkit - which is what makes every anti-abuse rule testable offline.
 *
 * <p>Two principles the whole plugin hangs on:
 *
 * <ul>
 *   <li><b>UNKNOWN never hurts the player.</b> A failed API check blocks new claims (we could
 *       not verify) but never triggers a revoke (we could not verify!). Revokes require a
 *       confirmed {@link SubscriptionStatus#NOT_SUBSCRIBED}.</li>
 *   <li><b>Revokes are remembered.</b> A SUBSCRIBE reward that was rolled back stays locked
 *       unless the admin marked it reclaimable - otherwise subscribe → claim → unsubscribe →
 *       resubscribe would farm the reward forever.</li>
 * </ul>
 */
public final class RewardEngine {

    private RewardEngine() {
    }

    /** Why a reward is or is not claimable at this moment. */
    public enum State {
        /** Claim allowed right now. */
        AVAILABLE,
        /** Some required network is not linked yet. */
        NEED_LINK,
        /** Linked, but a required subscription is confirmed missing. */
        NEED_SUBSCRIBE,
        /** A required check failed; claiming is paused, nothing is revoked. */
        CHECK_FAILED,
        /** SUBSCRIBE reward already granted and still in force. */
        ALREADY_CLAIMED,
        /** PERIODIC reward claimed recently; wait out the cooldown. */
        COOLDOWN,
        /** SUBSCRIBE reward was revoked earlier and is not reclaimable. */
        LOCKED
    }

    /**
     * @param state           the verdict
     * @param missing         for NEED_LINK / NEED_SUBSCRIBE / CHECK_FAILED: which networks
     * @param remainingMillis for COOLDOWN: time until the next claim, always &gt; 0
     */
    public record Availability(State state, List<Network> missing, long remainingMillis) {
        static Availability of(State state) {
            return new Availability(state, List.of(), 0L);
        }

        static Availability of(State state, List<Network> missing) {
            return new Availability(state, List.copyOf(missing), 0L);
        }

        static Availability cooldown(long remainingMillis) {
            return new Availability(State.COOLDOWN, List.of(), remainingMillis);
        }
    }

    /**
     * Full claimability check, in the order the player can actually fix things: link first,
     * then subscribe, then wait. Lock and already-claimed outrank everything - no amount of
     * subscribing changes them.
     */
    public static Availability availability(RewardDef reward,
                                            PlayerLinks links,
                                            Map<Network, SubscriptionStatus> statuses,
                                            ClaimState claim,
                                            long now) {
        if (reward.type() == RewardDef.Type.SUBSCRIBE) {
            if (claim.activeClaim()) {
                return Availability.of(State.ALREADY_CLAIMED);
            }
            if (claim.revokedAt() > 0 && !reward.reclaimable()) {
                return Availability.of(State.LOCKED);
            }
        }

        List<Network> notLinked = new ArrayList<>();
        for (Network n : reward.requires()) {
            if (!links.isLinked(n)) {
                notLinked.add(n);
            }
        }
        if (!notLinked.isEmpty()) {
            return Availability.of(State.NEED_LINK, notLinked);
        }

        List<Network> notSubscribed = new ArrayList<>();
        List<Network> unknown = new ArrayList<>();
        for (Network n : reward.requires()) {
            switch (statuses.getOrDefault(n, SubscriptionStatus.UNKNOWN)) {
                case NOT_SUBSCRIBED -> notSubscribed.add(n);
                case UNKNOWN -> unknown.add(n);
                case SUBSCRIBED -> { /* fine */ }
            }
        }
        if (!notSubscribed.isEmpty()) {
            return Availability.of(State.NEED_SUBSCRIBE, notSubscribed);
        }
        if (!unknown.isEmpty()) {
            return Availability.of(State.CHECK_FAILED, unknown);
        }

        if (reward.type() == RewardDef.Type.PERIODIC && claim.everClaimed()) {
            long readyAt = claim.lastClaimedAt() + reward.periodHours() * 3_600_000L;
            if (readyAt > now) {
                return Availability.cooldown(readyAt - now);
            }
        }
        return Availability.of(State.AVAILABLE);
    }

    /**
     * Whether an active SUBSCRIBE claim must be rolled back given fresh statuses.
     *
     * <p>Only a confirmed NOT_SUBSCRIBED triggers this. UNKNOWN keeps the claim: revoking
     * because an API timed out would punish players for our network problems, and that is
     * the fastest way to lose a server's trust.
     */
    public static boolean shouldRevoke(RewardDef reward,
                                       Map<Network, SubscriptionStatus> statuses,
                                       ClaimState claim) {
        if (reward.type() != RewardDef.Type.SUBSCRIBE || !claim.activeClaim()) {
            return false;
        }
        for (Network n : reward.requires()) {
            if (statuses.getOrDefault(n, SubscriptionStatus.UNKNOWN)
                    == SubscriptionStatus.NOT_SUBSCRIBED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Substitutes {@code %player%} and {@code %uuid%} in a configured console command.
     * Nothing else is expanded here; PlaceholderAPI, when present, runs in the bukkit layer.
     */
    public static String expandCommand(String template, String playerName, UUID uuid) {
        return template
                .replace("%player%", playerName)
                .replace("%uuid%", uuid.toString());
    }
}
