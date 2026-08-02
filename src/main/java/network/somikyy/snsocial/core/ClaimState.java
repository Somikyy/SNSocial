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

/**
 * What the storage knows about one player × one reward.
 *
 * <p>All instants are epoch millis. {@code lastClaimedAt} drives periodic cooldowns;
 * {@code revokedAt} non-zero means the last SUBSCRIBE grant was rolled back and, unless the
 * reward is reclaimable, stays locked - that is the anti-abuse memory that survives restarts.
 *
 * @param rewardId      the {@link RewardDef#id()}
 * @param timesClaimed  how many grants ever happened; 0 = never claimed
 * @param lastClaimedAt epoch millis of the latest grant, 0 when never claimed
 * @param revokedAt     epoch millis of the latest revoke, 0 when never revoked
 */
public record ClaimState(String rewardId, int timesClaimed, long lastClaimedAt, long revokedAt) {

    public static ClaimState fresh(String rewardId) {
        return new ClaimState(rewardId, 0, 0L, 0L);
    }

    public boolean everClaimed() {
        return timesClaimed > 0;
    }

    /** Claim currently in force: granted at least once and not revoked since. */
    public boolean activeClaim() {
        return everClaimed() && revokedAt < lastClaimedAt;
    }

    public ClaimState afterClaim(long now) {
        return new ClaimState(rewardId, timesClaimed + 1, now, revokedAt);
    }

    public ClaimState afterRevoke(long now) {
        return new ClaimState(rewardId, timesClaimed, lastClaimedAt, now);
    }
}
