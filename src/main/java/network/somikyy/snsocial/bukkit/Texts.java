/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.somikyy.snsocial.core.Messages;
import org.bukkit.command.CommandSender;

/**
 * Bridge from {@link Messages} (plain key=value texts) to Adventure components.
 *
 * <p>Two families of keys with different contracts: regular keys may carry MiniMessage
 * markup and are deserialized for chat and GUI; {@code bot.*} keys are sent to Telegram/VK
 * as-is and must stay plain text - the bots render no markup, and a stray {@code <green>}
 * in a bot reply reads as a bug to the player. {@link #raw} exists for exactly that path.
 */
final class Texts {

    private final Messages messages;
    private final boolean russian;

    Texts(Messages messages, boolean russian) {
        this.messages = messages;
        this.russian = russian;
    }

    /** Deserialized MiniMessage component for chat or GUI. */
    Component mm(String key, String... placeholders) {
        return MiniMessage.miniMessage()
                .deserialize(messages.get(key, russian, placeholders));
    }

    /** The raw text - for bot replies and console lines that must stay markup-free. */
    String raw(String key, String... placeholders) {
        return messages.get(key, russian, placeholders);
    }

    void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(mm(key, placeholders));
    }

    /** "3 д 4 ч", "2 ч 15 мин", "45 сек" - for cooldown countdowns, localized units. */
    String duration(long millis) {
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        String d = raw("time.days");
        String h = raw("time.hours");
        String m = raw("time.minutes");
        if (days > 0) {
            return days + " " + d + (hours > 0 ? " " + hours + " " + h : "");
        }
        if (hours > 0) {
            return hours + " " + h + (minutes > 0 ? " " + minutes + " " + m : "");
        }
        if (minutes > 0) {
            return minutes + " " + m;
        }
        return (millis / 1000) + " " + raw("time.seconds");
    }
}
