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

import java.util.Locale;

/** The two social networks SNSocial verifies. Config and storage refer to them by id. */
public enum Network {
    TELEGRAM("telegram"),
    VK("vk");

    private final String id;

    Network(String id) {
        this.id = id;
    }

    /** Stable lower-case id used in config keys, storage columns and command arguments. */
    public String id() {
        return id;
    }

    /** Parses a config/command token; null when the token names no known network. */
    public static Network fromId(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (Network n : values()) {
            if (n.id.equals(t)) {
                return n;
            }
        }
        // Common Russian shorthand people will inevitably type.
        if ("тг".equals(t) || "телеграм".equals(t) || "телеграмм".equals(t) || "tg".equals(t)) {
            return TELEGRAM;
        }
        if ("вк".equals(t) || "вконтакте".equals(t)) {
            return VK;
        }
        return null;
    }
}
