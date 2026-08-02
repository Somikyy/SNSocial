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
 * Which social accounts a player has linked. Null id = not linked.
 *
 * <p>Telegram and VK ids are {@code Long}, never int: Telegram user ids crossed 2^31 years
 * ago, and silently truncating one links the reward to somebody else's account.
 */
public record PlayerLinks(UUID player, Long telegramId, Long vkId) {

    public Long idOf(Network network) {
        return network == Network.TELEGRAM ? telegramId : vkId;
    }

    public boolean isLinked(Network network) {
        return idOf(network) != null;
    }

    public PlayerLinks with(Network network, Long id) {
        return network == Network.TELEGRAM
                ? new PlayerLinks(player, id, vkId)
                : new PlayerLinks(player, telegramId, id);
    }

    public static PlayerLinks none(UUID player) {
        return new PlayerLinks(player, null, null);
    }
}
