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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every string the user reads, kept out of the code and out in {@code messages.yml}.
 *
 * <p>Three layers, highest first: the admin's {@code plugins/SNSocial/messages.yml}, the
 * bundled texts of the chosen language, the bundled texts of the other one. The last layer is
 * what keeps a half-finished translation costing the reader one sentence in the wrong language
 * instead of a blank line, and the first is what makes a deleted key harmless - the point of
 * this file is that an admin cannot break the plugin with it.
 *
 * <p>One consequence of that order is worth stating out loud, because it is what an admin
 * trips over: the file covers every key, so once it exists the language flag in
 * {@code config.yml} no longer chooses the language - it only chose which bundle the file was
 * seeded from. The file therefore carries the language it was written in, and
 * {@link #languageMismatch} turns a silent "why is it still Russian" into a line in the log.
 *
 * <p>Values carry colour codes in whatever notation the admin likes ({@link Colors}) and
 * {@code {name}} placeholders. {@code {prefix}} is a placeholder like any other, pointing at the
 * {@code prefix} key - which is the whole answer to "how do I take the plugin name out of every
 * message": empty that one value and it is gone from all of them.
 */
public final class Messages {

    /** The key whose value is pasted in wherever {@code {prefix}} appears. */
    private static final String PREFIX_KEY = "prefix";

    /** The key the bundles stamp with their own language, read back by {@link #languageMismatch}. */
    private static final String LANGUAGE_KEY = "language";

    private final Map<String, String> russian;
    private final Map<String, String> english;
    private final Map<String, String> overrides;

    private Messages(Map<String, String> russian, Map<String, String> english,
            Map<String, String> overrides) {
        this.russian = russian;
        this.english = english;
        this.overrides = overrides;
    }

    /** Only what is bundled in the jar. */
    public static Messages bundled() {
        return load(null);
    }

    /**
     * Bundled texts with the admin's {@code messages.yml} laid over the top.
     *
     * @param messagesYml the admin's file, may be {@code null} or missing
     */
    public static Messages load(Path messagesYml) {
        Map<String, String> ru = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        readResource("/snsocial/messages-ru.yml", ru);
        readResource("/snsocial/messages-en.yml", en);
        Map<String, String> user = new LinkedHashMap<>();
        String text = readFile(messagesYml);
        if (text != null) {
            collect(MiniYaml.parse(text), user);
        }
        return new Messages(ru, en, user);
    }

    /**
     * The text for a key, or the key itself when it is missing everywhere, with
     * {@code {prefix}} already resolved.
     */
    public String get(String key, boolean russian) {
        return fill(lookup(key, russian), PREFIX_KEY, lookup(PREFIX_KEY, russian));
    }

    /**
     * The text for a key with {@code {name}} placeholders filled in. Names rather than
     * positions: a translator reordering a sentence moves {@code {player}} without counting
     * arguments.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public String get(String key, boolean russian, String... placeholders) {
        return fill(get(key, russian), placeholders);
    }

    /**
     * Replaces {@code {name}} placeholders in an already-resolved text. Separate from
     * {@link #get} so the bukkit layer can convert colours on the template first and leave
     * whatever a player name or a reward title dropped into the placeholder out of it.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public static String fill(String text, String... placeholders) {
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            if (placeholders[i + 1] != null) {
                result = result.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
        }
        return result;
    }

    /** True when the key exists in the given language. Used by the self-test. */
    public boolean has(String key, boolean russian) {
        return overrides.containsKey(key)
                || (russian ? this.russian : this.english).containsKey(key);
    }

    /** Every key present in either bundled language, sorted. Used by the self-test. */
    public Set<String> keys() {
        Set<String> all = new TreeSet<>(russian.keySet());
        all.addAll(english.keySet());
        return Collections.unmodifiableSet(all);
    }

    /**
     * The line for the log when {@code messages.yml} was written in one language and
     * {@code config.yml} now asks for the other, or {@code null} when they agree.
     *
     * <p>Changing {@code general.language} on a server that has already started looks like it
     * does nothing: the file is the top layer and it is in the old language. Deleting the file
     * is the answer, and saying so once in the log is cheaper for everyone than an issue.
     *
     * @param russian what {@code config.yml} asks for right now
     */
    public String languageMismatch(boolean russian) {
        String declared = overrides.get(LANGUAGE_KEY);
        String wanted = russian ? "ru" : "en";
        if (declared == null || declared.trim().equalsIgnoreCase(wanted)) {
            return null;
        }
        return "В config.yml выбран язык " + wanted + ", а файл messages.yml написан на языке "
                + declared.trim() + " — плагин говорит на языке файла, потому что тексты берутся"
                + " из него. Переведите messages.yml или удалите его: файл создастся заново на"
                + " выбранном языке.";
    }

    private String lookup(String key, boolean preferRussian) {
        String value = overrides.get(key);
        if (value != null) {
            return value;
        }
        Map<String, String> first = preferRussian ? this.russian : this.english;
        Map<String, String> second = preferRussian ? this.english : this.russian;
        value = first.get(key);
        if (value == null) {
            value = second.get(key);
        }
        return value != null ? value : key;
    }

    // ---------------------------------------------------------------- installing

    /**
     * Puts {@code messages.yml} in the plugin folder if it is not there yet, carrying over the
     * admin's edits to the {@code messages-*.txt} override file of 26.8.1 in the active
     * language. The other language's file cannot be carried into a single-language file, so it
     * is left where it is and named in the log instead of vanishing quietly.
     *
     * <p>The file is seeded from the bundled template of the active language and never touched
     * again - comments, section order and all. Migration rewrites values inside that template
     * rather than dumping a flat map, so an admin who edited three lines in the old format opens
     * the new file and finds their three lines in place, with the documentation around them.
     *
     * @return lines for the server log; empty when there was nothing to do
     */
    public static List<String> install(Path dataFolder, boolean russian) {
        List<String> log = new ArrayList<>();
        if (dataFolder == null) {
            return log;
        }
        Path target = dataFolder.resolve("messages.yml");
        if (Files.exists(target)) {
            return log;
        }
        String template = readResourceText(russian
                ? "/snsocial/messages-ru.yml" : "/snsocial/messages-en.yml");
        if (template == null) {
            return log;
        }
        Path legacy = dataFolder.resolve(russian ? "messages-ru.txt" : "messages-en.txt");
        Map<String, String> carried = readLegacy(legacy);
        String prefix = carried.get(PREFIX_KEY);
        if (prefix != null && !prefix.isEmpty() && !prefix.endsWith(" ")) {
            // In 26.8.1 the "prefix" key was read by nothing: the brand was typed into every
            // line by hand, and the space after it along with it. Now that the key is what
            // every line starts with, a carried-over value has to bring that space with it or
            // the admin's own prefix ends up glued to the first word.
            carried.put(PREFIX_KEY, prefix + " ");
        }
        if (!carried.isEmpty()) {
            template = applyOverrides(template, carried);
        }
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(target, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.add("Не удалось создать messages.yml (" + e.getMessage()
                    + "). Плагин работает на встроенных текстах.");
            return log;
        }
        log.add("Создан файл messages.yml — все тексты плагина теперь правятся там.");
        if (!carried.isEmpty()) {
            // "Ваши N строк" is wrong Russian for most values of N, and a log line is not the
            // place for a plural helper: the colon takes the number out of the sentence.
            log.add("Перенесено строк из " + legacy.getFileName() + ": " + carried.size()
                    + ". Старый файл больше не читается — его можно удалить.");
        }
        Path otherLegacy = dataFolder.resolve(russian ? "messages-en.txt" : "messages-ru.txt");
        if (Files.exists(otherLegacy)) {
            // Only one language is carried over, because only one language ends up in the new
            // file. An admin who had edited both has to be told which half was left behind.
            log.add("Файл " + otherLegacy.getFileName() + " остался нетронутым: переносится"
                    + " только тот язык, который выбран в config.yml.");
        }
        return log;
    }

    /**
     * Rewrites the scalar values of {@code template} whose dotted key appears in
     * {@code values}, leaving every other line - comments included - byte for byte.
     *
     * <p>Indentation is tracked the same way {@link MiniYaml} tracks it, so the two agree on
     * what {@code link.telegram.instructions} means. A key in {@code values} that the template
     * does not have is dropped rather than appended: it is a key from a version that no longer
     * exists.
     */
    static String applyOverrides(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String[] lines = template.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                out.append('\n');
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                out.append(line);
                continue;
            }
            int colon = keyColon(trimmed);
            if (colon < 0) {
                out.append(line);
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String key = unquote(trimmed.substring(0, colon).trim());
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                path.add(key);
                indents.add(indent);
                out.append(line);
                continue;
            }
            String replacement = values.get(fullKey);
            if (replacement == null) {
                out.append(line);
                continue;
            }
            out.append(line, 0, line.indexOf(trimmed))
                    .append(trimmed, 0, colon + 1)
                    .append(' ')
                    .append(quote(replacement));
        }
        return out.toString();
    }

    /** Wraps a value in YAML single quotes, doubling the apostrophes inside. */
    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- loading

    /** Flattens a parsed file into key -> text, keeping blank values blank. */
    private static void collect(MiniYaml yaml, Map<String, String> out) {
        for (String key : yaml.paths()) {
            String value = yaml.getRaw(key);
            if (value != null) {
                out.put(key, value);
            } else if (yaml.isBlank(key)) {
                // "prefix:" with nothing after it is an admin deleting the text, not forgetting
                // to write one. The difference matters exactly here.
                out.put(key, "");
            }
        }
    }

    /** The 26.8.1 format: one {@code key=value} per line, split on the first equals sign. */
    private static Map<String, String> readLegacy(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isReadable(file)) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        } catch (IOException ignored) {
            // A broken old file must not stop the new one from being written.
        }
        return out;
    }

    private static void readResource(String resource, Map<String, String> out) {
        String text = readResourceText(resource);
        if (text != null) {
            collect(MiniYaml.parse(text), out);
        }
    }

    private static String readResourceText(String resource) {
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException e) {
            // A missing bundled file degrades to raw keys; it must not break the plugin.
            return null;
        }
    }

    private static String readFile(Path file) {
        if (file == null || !Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A broken override file must not break the plugin either.
            return null;
        }
    }

    // ------------------------------------------------------- template rewriting bits

    /** Same rule as {@link MiniYaml}: the colon that ends a key is followed by a space or EOL. */
    private static int keyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i == s.length() - 1 || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == '\'' && last == '\'') {
                return s.substring(1, s.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
