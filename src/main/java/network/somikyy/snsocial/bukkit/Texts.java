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
import network.somikyy.snsocial.core.Colors;
import network.somikyy.snsocial.core.Messages;
import org.bukkit.command.CommandSender;

/**
 * Bridge from {@link Messages} (the texts of {@code messages.yml}) to Adventure components.
 *
 * <p>Two families of keys with different contracts: regular keys are rendered as markup for
 * chat and the GUI; {@code bot.*} keys are sent to Telegram/VK, which draw no markup at all,
 * so for those the colours are removed rather than rendered. {@link #raw} is that path.
 *
 * <p>Both paths convert the admin's colour codes first and substitute placeholders second.
 * The order is the point: a player name or a reward title landing in {@code {reward}} is then
 * read as text, not as a colour instruction of its own. Values that are the admin's own -
 * reward titles and lore from {@code config.yml} - are converted where they are read, in
 * {@link SNSocialConfig}, so they get the same four notations without passing through here.
 */
final class Texts {

    private final Messages messages;
    private final boolean russian;

    Texts(Messages messages, boolean russian) {
        this.messages = messages;
        this.russian = russian;
    }

    /** Deserialized MiniMessage component for chat or the GUI. */
    Component mm(String key, String... placeholders) {
        String template = Colors.toMiniMessage(messages.get(key, russian));
        return MiniMessage.miniMessage().deserialize(Messages.fill(template, placeholders));
    }

    /**
     * The plain text - for bot replies, console lines and words substituted into other
     * messages, all of which must stay markup-free.
     *
     * <p>Colours are stripped rather than trusted to be absent: the file is the admin's, and
     * an {@code &a} pasted into a bot text should cost them nothing worse than a colourless
     * message. A {@code <игрок>} in the same string is not markup and survives.
     *
     * <p>The literal two-character sequence {@code \n} becomes a newline here: the messages
     * format is strictly one line per key, but a bot reply sometimes needs line breaks.
     * Game-side keys use MiniMessage's own {@code <newline>} tag instead and are unaffected.
     */
    String raw(String key, String... placeholders) {
        String template = Colors.strip(messages.get(key, russian));
        return Messages.fill(template, placeholders).replace("\\n", "\n");
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
