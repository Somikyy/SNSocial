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
import java.util.Set;

/**
 * One reward as the admin configured it.
 *
 * <p>Immutable and Bukkit-free on purpose: the icon is a material <em>name</em>, not a
 * Material, so the reward engine and its tests run without a server. The bukkit layer
 * resolves the name and falls back to a default when an admin typo produces no material.
 *
 * @param id             config key; also the storage key, so renaming a reward resets claims
 * @param requires       networks the player must be subscribed to, all at once
 * @param type           one-shot subscription reward or a repeating one
 * @param periodHours    for {@link Type#PERIODIC}: hours between claims; ignored otherwise
 * @param commands       console commands run on grant, with {@code %player%} placeholders
 * @param revokeCommands console commands run when the subscription reward is rolled back
 * @param reclaimable    whether a SUBSCRIBE reward may be claimed again after a revoke
 * @param autoClaim      grant automatically when a check finds the reward available
 * @param displayName    MiniMessage text for chat and the GUI item title
 * @param description    MiniMessage lore lines for the GUI item
 * @param icon           Bukkit material name for the GUI item, resolved by the bukkit layer
 * @param slot           fixed GUI slot, or -1 to lay out automatically
 */
public record RewardDef(
        String id,
        Set<Network> requires,
        Type type,
        int periodHours,
        List<String> commands,
        List<String> revokeCommands,
        boolean reclaimable,
        boolean autoClaim,
        String displayName,
        List<String> description,
        String icon,
        int slot) {

    /** How the reward repeats. */
    public enum Type {
        /** Once per subscription; revocable when the player unsubscribes. */
        SUBSCRIBE,
        /** Claimable every {@code periodHours} while the player stays subscribed. */
        PERIODIC;

        /** Parses a config token; null for unknown tokens so the caller can report the key. */
        public static Type fromId(String token) {
            if (token == null) {
                return null;
            }
            return switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "subscribe", "one-time", "onetime" -> SUBSCRIBE;
                case "periodic", "repeat", "repeating" -> PERIODIC;
                default -> null;
            };
        }
    }

    public RewardDef {
        requires = Set.copyOf(requires);
        commands = List.copyOf(commands);
        revokeCommands = List.copyOf(revokeCommands);
        description = List.copyOf(description);
    }
}
