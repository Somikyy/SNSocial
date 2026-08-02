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
 * What a membership check actually established.
 *
 * <p>{@code UNKNOWN} is deliberately a first-class value, not an exception path: both APIs
 * fail routinely (timeouts, rate limits, revoked tokens), and the reward engine must treat
 * "could not check" differently from "checked and not subscribed" - nobody's reward may be
 * revoked because Telegram had a bad minute.
 */
public enum SubscriptionStatus {
    /** Confirmed member/subscriber. */
    SUBSCRIBED,
    /** Confirmed not a member: left, kicked, or never joined. */
    NOT_SUBSCRIBED,
    /** The check itself failed; keep previous knowledge and try again later. */
    UNKNOWN
}
