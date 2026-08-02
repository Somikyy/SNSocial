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

/**
 * Reads the players who already received their reward from fmSocialReward.
 *
 * <p>fmSocialReward (FeyMer31, abandoned 2023) stored grants straight in its config.yml as
 * top-level keys: {@code config.set("player-" + name, "received")} - verified against the
 * original source, github.com/FeyMer31/fmSocialReward, FmSocialReward.java. So the "database"
 * is lines of the form {@code player-<Nick>: received}, possibly with the key quoted by the
 * YAML writer when the nick needs it.
 *
 * <p>This parser reads exactly that: top-level keys with the {@code player-} prefix. It is
 * not a YAML parser and does not want to be one - the file has one known writer, and nicks
 * cannot contain newlines or colons (Mojang allows only {@code [A-Za-z0-9_]}), so line-based
 * reading is exact, not approximate.
 */
public final class FmSocialRewardImport {

    private static final String PREFIX = "player-";

    private FmSocialRewardImport() {
    }

    /** Nicknames with a recorded grant, in file order, duplicates removed. */
    public static List<String> parseNicknames(String configYml) {
        List<String> out = new ArrayList<>();
        for (String rawLine : configYml.split("\n", -1)) {
            // Top-level keys only: fmSocialReward wrote grants at the root, and an indented
            // "player-..." would be someone else's data.
            if (rawLine.isEmpty() || rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t'
                    || rawLine.charAt(0) == '#') {
                continue;
            }
            int colon = rawLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = rawLine.substring(0, colon).trim();
            if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')
                    && key.charAt(key.length() - 1) == key.charAt(0)) {
                key = key.substring(1, key.length() - 1);
            }
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String nick = key.substring(PREFIX.length()).trim();
            if (!nick.isEmpty() && !out.contains(nick)) {
                out.add(nick);
            }
        }
        return out;
    }
}
