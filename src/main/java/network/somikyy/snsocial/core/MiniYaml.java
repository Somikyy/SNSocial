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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small YAML reader, here for exactly one file: {@code messages.yml}.
 *
 * <p>Bukkit ships SnakeYAML and the plugin reads {@code config.yml} through it. The texts are
 * read by this parser instead, for one reason: {@code core} must not import {@code org.bukkit}
 * - that is what lets the message loader be compiled and asserted on by the offline self-test,
 * with no server jar and no network. The same parser runs in the plugin and in the test, so a
 * file that passes the test is a file the server will read identically.
 *
 * <p>Trimmed to what {@link Messages} asks of it - scalars, sections and the difference between
 * a blank value and a missing one. The config-shaped getters of the sibling plugins (numbers,
 * booleans, clamping) are not here because nothing in this repo would call them.
 *
 * <p>Supported subset:
 * <ul>
 *   <li>{@code key: value} scalars, nested maps flattened to dotted keys</li>
 *   <li>{@code #} comments, quoted keys and scalars, blank lines</li>
 * </ul>
 * Anything more exotic is skipped rather than treated as an error: one unusual line must not
 * cost the admin every other text in the file.
 */
public final class MiniYaml {

    private final Map<String, String> scalars = new LinkedHashMap<>();

    /** Every dotted key ever seen, sections included, in file order. */
    private final Set<String> paths = new LinkedHashSet<>();

    /**
     * Keys that turned out to own a block list. The items themselves are thrown away - no text
     * is a list - but the fact is kept, so {@link #isBlank} does not mistake a list header for
     * a deliberately emptied value.
     */
    private final Set<String> listOwners = new LinkedHashSet<>();

    private MiniYaml() {
    }

    public static MiniYaml parse(String text) {
        MiniYaml yaml = new MiniYaml();
        yaml.doParse(text);
        return yaml;
    }

    // ---------------------------------------------------------------- reading

    /**
     * The scalar exactly as written, or {@code null} when the key carries no scalar at all.
     *
     * <p>Raw on purpose: for a setting, a blank value means "I did not fill this in" and
     * falling back to the default is right. For a message it means the opposite -
     * {@code prefix: ''} is an admin deliberately deleting the plugin name from every line,
     * and answering that with the default would be the plugin arguing with them.
     */
    public String getRaw(String key) {
        return scalars.get(key);
    }

    /**
     * True when the key was written with no value at all ({@code prefix:}) rather than with a
     * blank one - and is a leaf, not a section header. YAML calls that null; for messages it
     * means the same thing as {@code ''}.
     */
    public boolean isBlank(String key) {
        return paths.contains(key) && !scalars.containsKey(key) && !listOwners.contains(key)
                && !hasChildren(key);
    }

    /** Every dotted key in the file, sections included, in the order they were written. */
    public Set<String> paths() {
        return Collections.unmodifiableSet(paths);
    }

    private boolean hasChildren(String prefix) {
        String head = prefix + ".";
        for (String path : paths) {
            if (path.startsWith(head)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- parsing

    private void doParse(String text) {
        // path[i] holds the key owning indentation level i
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String listOwner = null;
        int listIndent = -1;

        for (String rawLine : text.split("\r?\n", -1)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();

            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (listOwner != null && indent >= listIndent) {
                    listOwners.add(listOwner);
                }
                continue;
            }

            int colon = findKeyColon(trimmed);
            if (colon < 0) {
                continue; // not a mapping line we understand
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = trimmed.substring(colon + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            // pop deeper-or-equal levels off the path
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            paths.add(fullKey);

            if (value.isEmpty()) {
                // either a nested map, an emptied text or the header of a block list - which
                // one it is is not knowable until the next line
                path.add(key);
                indents.add(indent);
                listOwner = fullKey;
                listIndent = indent;
            } else {
                scalars.put(fullKey, unquote(value));
                listOwner = null;
            }
        }
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Finds the mapping colon, ignoring colons inside quotes. */
    private static int findKeyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':') {
                // "key:" or "key: value" - a colon inside a bare scalar (a URL, a time) has no
                // trailing space and is not at end of line
                if (i == s.length() - 1 || s.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == '\'' && last == '\'') {
                // YAML's single-quote escape. "it''s me" is an ordinary thing for a translator
                // to write, and losing the apostrophe - or worse, ending the value early -
                // would be a bug with no visible cause.
                return s.substring(1, s.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
