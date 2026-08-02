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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every string the user reads, kept out of the code. Same arrangement as the rest of the SN
 * line: {@code key=value}, one line each, everything after the first {@code =} taken
 * literally - a format with no escaping rules is a format nobody can get wrong. Values may
 * carry MiniMessage markup; the bukkit layer deserializes them.
 *
 * <p>Bundled texts live in {@code snsocial/messages-<lang>.txt} inside the jar and can be
 * partially overridden from the plugin folder: a file with one line replaces one text and
 * leaves the rest alone.
 */
public final class Messages {

    private final Map<String, String> russian;
    private final Map<String, String> english;

    private Messages(Map<String, String> russian, Map<String, String> english) {
        this.russian = russian;
        this.english = english;
    }

    /** Only what is bundled in the jar. */
    public static Messages bundled() {
        return load(null, null);
    }

    /**
     * Bundled texts with optional user overrides laid over the top.
     *
     * @param russianOverride file overriding Russian texts, may be {@code null}
     * @param englishOverride file overriding English texts, may be {@code null}
     */
    public static Messages load(Path russianOverride, Path englishOverride) {
        Map<String, String> ru = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        readResource("/snsocial/messages-ru.txt", ru);
        readResource("/snsocial/messages-en.txt", en);
        readOverride(russianOverride, ru);
        readOverride(englishOverride, en);
        return new Messages(ru, en);
    }

    /**
     * The text for a key, or the key itself when it is missing. Falls back to the other
     * language first: an incomplete translation should cost the reader one sentence in the
     * wrong language, not a blank message.
     */
    public String get(String key, boolean russian) {
        Map<String, String> first = russian ? this.russian : this.english;
        Map<String, String> second = russian ? this.english : this.russian;
        String value = first.get(key);
        if (value == null) {
            value = second.get(key);
        }
        return value != null ? value : key;
    }

    /**
     * The text for a key with {@code {name}} placeholders filled in. Names rather than
     * positions: a translator reordering a sentence moves {@code {player}} without counting
     * arguments.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public String get(String key, boolean russian, String... placeholders) {
        String text = get(key, russian);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return text;
    }

    /** True when the key exists in the given language. Used by the self-test. */
    public boolean has(String key, boolean russian) {
        return (russian ? this.russian : this.english).containsKey(key);
    }

    /** Every key present in either language, sorted. Used by the self-test. */
    public Set<String> keys() {
        Set<String> all = new TreeSet<>(russian.keySet());
        all.addAll(english.keySet());
        return Collections.unmodifiableSet(all);
    }

    // ---------------------------------------------------------------- loading

    private static void readResource(String resource, Map<String, String> out) {
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in != null) {
                read(new InputStreamReader(in, StandardCharsets.UTF_8), out);
            }
        } catch (IOException ignored) {
            // A missing bundled file degrades to raw keys; it must not break the plugin.
        }
    }

    private static void readOverride(Path file, Map<String, String> out) {
        if (file == null || !Files.isReadable(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            read(reader, out);
        } catch (IOException ignored) {
            // A broken override file must not break the plugin either.
        }
    }

    private static void read(Reader reader, Map<String, String> out) throws IOException {
        BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            // Split on the FIRST '=' only: values contain them, keys never do.
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
    }
}
