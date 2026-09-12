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

/**
 * Colour codes an admin might write in {@code messages.yml}, translated into MiniMessage.
 *
 * <p>The rule the line follows: an admin writes colours the way they already know how, and the
 * plugin works it out. Four notations are accepted inside the same string -
 * <ul>
 *   <li>{@code &c}, {@code &l}, {@code &r} - the codes everyone has typed since 2011
 *       ({@code §} works too, for text pasted out of another plugin's config);</li>
 *   <li>{@code &#7B2FFF} - the short HEX form, the one people actually ask for;</li>
 *   <li>{@code &x&7&B&2&F&F&F} - the long HEX form Spigot's own serializer emits, so a value
 *       copied out of a Spigot-era config keeps its colour;</li>
 *   <li>{@code <red>}, {@code <#7B2FFF>}, {@code <gradient:...>} - MiniMessage, left untouched.</li>
 * </ul>
 *
 * <p>Deliberately dependency-free and Bukkit-free: the conversion is pure string work, which is
 * what lets the offline self-test assert on it without a server jar, and what lets this file be
 * copied unchanged into every other plugin of the line.
 */
public final class Colors {

    /** Hex digit -> MiniMessage colour name, in vanilla code order. */
    private static final String[] NAMED = {
        "black", "dark_blue", "dark_green", "dark_aqua",
        "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua",
        "red", "light_purple", "yellow", "white",
    };

    private static final String HEX_DIGITS = "0123456789abcdef";

    private static final char SECTION = '§';

    private Colors() {
    }

    /**
     * Rewrites legacy and HEX codes into MiniMessage tags, leaving existing tags alone.
     *
     * <p>One wrinkle worth the code it costs: in legacy chat a colour code also clears bold and
     * italic, in MiniMessage it does not. Left alone, {@code &c&lЖирный &aОбычный} would come
     * out with "Обычный" still bold - not what the person who typed it meant. When the value
     * carries no MiniMessage markup at all, this method reproduces the legacy behaviour exactly
     * by emitting {@code <reset>} ahead of each colour. When the value does carry markup, the
     * admin is already thinking in MiniMessage, and a stray {@code <reset>} there would close
     * their {@code <gradient>} or {@code <click>} and leave the matching {@code </gradient>}
     * dangling - so there the codes map straight across and nothing is closed behind their back.
     */
    public static String toMiniMessage(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean legacyOnly = !hasMarkup(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                // MiniMessage's own escape. The bracket after it is a bracket the player is
                // meant to see, not the start of a tag, so both characters go through as they
                // are and the scanner steps over them.
                out.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int close = tagEnd(text, i);
                // A tag is copied out whole, so nothing inside its arguments is touched. The
                // case that forces this: <click:open_url:https://site/?a=1&b=2> - the "&b" is
                // half a query string, and turning it into <aqua> breaks both the link and the
                // tag around it. Only tags the strip side also recognises are protected, so a
                // sentence like "<5 и &a>" still gets its colour code.
                if (close > i && isKnownTag(text.substring(i + 1, close))) {
                    out.append(text, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            if ((c != '&' && c != SECTION) || i + 1 >= text.length()) {
                out.append(c);
                i++;
                continue;
            }
            if (text.charAt(i + 1) == '#' && isHexRun(text, i + 2, 6)) {
                appendColour(out, "#" + lower(text, i + 2, 6), legacyOnly);
                i += 8;
                continue;
            }
            String longHex = readLongHex(text, i);
            if (longHex != null) {
                appendColour(out, "#" + longHex, legacyOnly);
                i += 14;
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            int named = HEX_DIGITS.indexOf(code);
            if (named >= 0) {
                appendColour(out, NAMED[named], legacyOnly);
                i += 2;
                continue;
            }
            String decoration = decoration(code);
            if (decoration != null) {
                appendTag(out, decoration);
                i += 2;
                continue;
            }
            if (code == 'r') {
                appendTag(out, "reset");
                i += 2;
                continue;
            }
            // A lone ampersand in ordinary text - "Том & Джерри" must survive untouched.
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The same text with every colour instruction removed - for sinks that render no markup at
     * all: Telegram and VK messages, console lines, log files.
     *
     * <p>Unknown tags are left alone on purpose. {@code <игрок>} inside a usage string is not
     * markup, it is the word the admin wrote, and eating it would be worse than leaving it.
     * For the same reason the search for a tag's closing bracket is bounded ({@link #tagEnd}):
     * a bare {@code <} in a sentence must not swallow the words up to the next {@code >}
     * somewhere further along the line.
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                // The escape is consumed and what it protected is kept: MiniMessage renders
                // "\<red>" as the literal characters "<red>", so the plain copy of a message
                // has to show the same thing the chat copy does.
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '&' || c == SECTION) {
                int width = codeWidth(text, i);
                if (width > 0) {
                    i += width;
                    continue;
                }
            }
            if (c == '<') {
                int close = tagEnd(text, i);
                if (close > i) {
                    String inner = text.substring(i + 1, close);
                    if (inner.equals("newline") || inner.equals("br")) {
                        out.append('\n');
                        i = close + 1;
                        continue;
                    }
                    if (isKnownTag(inner)) {
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static void appendColour(StringBuilder out, String colour, boolean legacyOnly) {
        if (legacyOnly) {
            appendTag(out, "reset");
        }
        appendTag(out, colour);
    }

    /**
     * Appends a tag, first making sure the text in front of it does not swallow it.
     *
     * <p>A value ending in a backslash right before a converted code - {@code "путь\&cтекст"} -
     * would otherwise produce {@code \<red>}, which MiniMessage reads as an escaped bracket and
     * prints as the literal text {@code <red>}. Doubling the backslash keeps it a backslash and
     * leaves the tag a tag.
     */
    private static void appendTag(StringBuilder out, String tag) {
        int backslashes = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        if (backslashes % 2 == 1) {
            out.append('\\');
        }
        out.append('<').append(tag).append('>');
    }

    /** True when the value looks like it already carries MiniMessage tags. */
    private static boolean hasMarkup(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '<') {
                continue;
            }
            char next = text.charAt(i + 1);
            // Same bounded scan as stripping uses, so the two answer "is this a tag" the same
            // way: a bare bracket in a sentence must not make the whole value look like markup.
            // "!" is in the list because <!italic> is how MiniMessage turns a decoration OFF,
            // and missing it is worse than missing an ordinary tag: the value would count as
            // markup-free, the legacy branch would emit <reset> in front of the next colour,
            // and that reset turns the decoration straight back on - cancelling exactly what
            // the admin wrote the tag to cancel.
            if ((next == '/' || next == '#' || next == '!' || Character.isLetter(next))
                    && tagEnd(text, i) > i) {
                return true;
            }
        }
        return false;
    }

    /**
     * Index of the {@code >} that closes a plausible tag opened at {@code i}, or -1.
     *
     * <p>Bounded rather than "the next {@code >} anywhere", because the unbounded search loses
     * text: in {@code "цена < 5 <red>рублей"} the first bracket would pair with the one of
     * {@code <red>} and the middle of the sentence would disappear.
     *
     * <p>The bound is a second {@code <}, and only that. Stopping at whitespace too would be a
     * tighter net and was tempting, but it costs a tag people really write:
     * {@code <click:run_command:/snsocial link telegram>} carries unquoted spaces and would
     * stop being recognised.
     *
     * <p>Quoted arguments are skipped over, because they may contain a whole tag of their own:
     * in {@code <hover:show_text:'<red>подсказка'>слово} the first {@code >} belongs to the
     * tooltip, not to the hover, and taking it would end the tag early and drop
     * {@code подсказка'>} into the text. A quote opens an argument only when it stands right
     * after the {@code :} that begins one - the same rule MiniMessage's own parser uses, and
     * the reason {@code <don't>} is still read as the word in brackets it obviously is.
     */
    private static int tagEnd(String text, int i) {
        char quote = 0;
        for (int at = i + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quote != 0) {
                if (c == '\\') {
                    at++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if ((c == '\'' || c == '"') && text.charAt(at - 1) == ':') {
                quote = c;
            } else if (c == '>') {
                return at;
            } else if (c == '<') {
                return -1;
            }
        }
        return -1;
    }

    /** Length of the legacy/HEX code starting at {@code i}, or 0 when there is none. */
    private static int codeWidth(String text, int i) {
        if (i + 1 >= text.length()) {
            return 0;
        }
        if (text.charAt(i + 1) == '#' && isHexRun(text, i + 2, 6)) {
            return 8;
        }
        if (readLongHex(text, i) != null) {
            return 14;
        }
        char code = Character.toLowerCase(text.charAt(i + 1));
        return HEX_DIGITS.indexOf(code) >= 0 || decoration(code) != null || code == 'r' ? 2 : 0;
    }

    /**
     * Reads Spigot's long HEX form {@code &x&7&B&2&F&F&F} at {@code i}, or {@code null}.
     *
     * <p>The markers may be mixed - {@code §x&7&B...} turns up in configs that went through a
     * half-finished search-and-replace - so each pair is checked for "a marker" rather than for
     * the same marker that opened the run.
     */
    private static String readLongHex(String text, int i) {
        if (i + 13 >= text.length() || Character.toLowerCase(text.charAt(i + 1)) != 'x') {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int pair = 0; pair < 6; pair++) {
            int at = i + 2 + pair * 2;
            char marker = text.charAt(at);
            char digit = Character.toLowerCase(text.charAt(at + 1));
            if ((marker != '&' && marker != SECTION) || HEX_DIGITS.indexOf(digit) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }

    private static boolean isHexRun(String text, int from, int length) {
        if (from < 0 || from + length > text.length()) {
            return false;
        }
        for (int i = from; i < from + length; i++) {
            if (HEX_DIGITS.indexOf(Character.toLowerCase(text.charAt(i))) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String lower(String text, int from, int length) {
        return text.substring(from, from + length).toLowerCase(Locale.ROOT);
    }

    private static String decoration(char code) {
        switch (code) {
            case 'k':
                return "obfuscated";
            case 'l':
                return "bold";
            case 'm':
                return "strikethrough";
            case 'n':
                return "underlined";
            case 'o':
                return "italic";
            default:
                return null;
        }
    }

    /**
     * True for MiniMessage tags {@link #strip} should swallow. Listing them rather than eating
     * every {@code <...>} is the whole point: the list is what separates markup from a word in
     * angle brackets that the admin meant to be read.
     */
    private static boolean isKnownTag(String inner) {
        String tag = inner.startsWith("/") ? inner.substring(1) : inner;
        // <!italic> is the same tag as <italic>, turned off. Recognising it matters on both
        // sides: the plain copy must drop it rather than print "<!italic>" at the reader, and
        // the converter must copy it out whole like any other tag.
        if (tag.startsWith("!")) {
            tag = tag.substring(1);
        }
        int colon = tag.indexOf(':');
        if (colon >= 0) {
            tag = tag.substring(0, colon);
        }
        tag = tag.toLowerCase(Locale.ROOT);
        if (tag.startsWith("#")) {
            return tag.length() == 7 && isHexRun(tag, 1, 6);
        }
        for (String name : NAMED) {
            if (name.equals(tag)) {
                return true;
            }
        }
        switch (tag) {
            case "reset":
            case "bold":
            case "b":
            case "italic":
            case "i":
            case "em":
            case "underlined":
            case "u":
            case "strikethrough":
            case "st":
            case "obfuscated":
            case "obf":
            case "color":
            case "colour":
            case "c":
            case "gradient":
            case "rainbow":
            case "transition":
            case "shadow_color":
            case "font":
            case "click":
            case "hover":
            case "insert":
            case "insertion":
            case "key":
            case "lang":
            case "tr":
            case "translate":
            case "selector":
            case "sel":
            case "score":
            case "nbt":
            case "data":
            case "pride":
                return true;
            default:
                return false;
        }
    }
}
