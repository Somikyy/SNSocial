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

import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.RewardDef;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Typed snapshot of config.yml, replaced wholesale on reload so work in flight keeps the
 * settings it started with.
 *
 * <p>Parsing never throws: a broken reward is skipped with a warning naming the exact key,
 * because "плагин не включился" over one typo in one reward is how admins lose an evening.
 * The warnings list is printed by the caller - collection is separated from logging so the
 * parse stays testable.
 */
record SNSocialConfig(
        boolean russian,
        boolean updateCheck,
        boolean telegramEnabled,
        String telegramToken,
        String telegramChannel,
        boolean vkEnabled,
        String vkToken,
        long vkGroupId,
        String vkApiUrl,
        int checkIntervalMinutes,
        boolean checkOnJoin,
        int joinDelaySeconds,
        int codeTtlMinutes,
        String storageType,
        String tablePrefix,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUser,
        String mysqlPassword,
        List<RewardDef> rewards,
        List<String> warnings) {

    static SNSocialConfig from(FileConfiguration yml) {
        List<String> warnings = new ArrayList<>();
        List<RewardDef> rewards = new ArrayList<>();

        ConfigurationSection rewardsSection = yml.getConfigurationSection("rewards");
        if (rewardsSection != null) {
            for (String id : rewardsSection.getKeys(false)) {
                RewardDef def = parseReward(rewardsSection.getConfigurationSection(id), id,
                        warnings);
                if (def != null) {
                    rewards.add(def);
                }
            }
        }
        if (rewards.isEmpty()) {
            warnings.add("В config.yml нет ни одной корректной награды (секция rewards).");
        }

        return new SNSocialConfig(
                !"en".equalsIgnoreCase(yml.getString("general.language", "ru")),
                yml.getBoolean("general.update-check", true),
                yml.getBoolean("telegram.enabled", false),
                yml.getString("telegram.bot-token", "").trim(),
                yml.getString("telegram.channel", "").trim(),
                yml.getBoolean("vk.enabled", false),
                yml.getString("vk.group-token", "").trim(),
                yml.getLong("vk.group-id", 0),
                yml.getString("vk.api-url", "https://api.vk.com").trim(),
                clamp(yml.getInt("check.interval-minutes", 60), 5, 24 * 60, warnings,
                        "check.interval-minutes"),
                yml.getBoolean("check.on-join", true),
                clamp(yml.getInt("check.join-delay-seconds", 5), 1, 300, warnings,
                        "check.join-delay-seconds"),
                clamp(yml.getInt("link.code-ttl-minutes", 10), 1, 120, warnings,
                        "link.code-ttl-minutes"),
                yml.getString("storage.type", "sqlite").trim().toLowerCase(Locale.ROOT),
                sanePrefix(yml.getString("storage.table-prefix", "snsocial_"), warnings),
                yml.getString("storage.mysql.host", "localhost"),
                yml.getInt("storage.mysql.port", 3306),
                yml.getString("storage.mysql.database", "minecraft"),
                yml.getString("storage.mysql.user", "root"),
                yml.getString("storage.mysql.password", ""),
                List.copyOf(rewards),
                List.copyOf(warnings));
    }

    private static RewardDef parseReward(ConfigurationSection section, String id,
                                         List<String> warnings) {
        if (section == null) {
            warnings.add("Награда '" + id + "' пропущена: это не секция.");
            return null;
        }
        Set<Network> requires = EnumSet.noneOf(Network.class);
        for (String token : section.getStringList("requires")) {
            Network network = Network.fromId(token);
            if (network == null) {
                warnings.add("Награда '" + id + "': неизвестная сеть '" + token
                        + "' в requires (жду telegram или vk).");
            } else {
                requires.add(network);
            }
        }
        if (requires.isEmpty()) {
            warnings.add("Награда '" + id + "' пропущена: пустой requires.");
            return null;
        }
        RewardDef.Type type = RewardDef.Type.fromId(section.getString("type", "subscribe"));
        if (type == null) {
            warnings.add("Награда '" + id + "' пропущена: неизвестный type '"
                    + section.getString("type") + "' (жду subscribe или periodic).");
            return null;
        }
        int periodHours = section.getInt("period-hours", 24);
        if (type == RewardDef.Type.PERIODIC && periodHours < 1) {
            warnings.add("Награда '" + id + "' пропущена: period-hours должен быть >= 1.");
            return null;
        }
        List<String> commands = section.getStringList("commands");
        if (commands.isEmpty()) {
            warnings.add("Награда '" + id + "' пропущена: пустой список commands.");
            return null;
        }
        return new RewardDef(
                id,
                requires,
                type,
                periodHours,
                commands,
                section.getStringList("revoke-commands"),
                section.getBoolean("reclaimable", false),
                section.getBoolean("auto-claim", false),
                section.getString("display-name", id),
                section.getStringList("description"),
                section.getString("icon", "CHEST"),
                section.getInt("slot", -1));
    }

    /**
     * The prefix is concatenated into DDL and every query - it may contain nothing but
     * identifier characters, whatever the admin typed. Not a security boundary (the admin
     * owns the database anyway), but a typo like "snsocial_;" must fail loudly here, not
     * as a SQLSyntaxErrorException three layers deeper.
     */
    private static String sanePrefix(String prefix, List<String> warnings) {
        String trimmed = prefix.trim();
        if (!trimmed.matches("[A-Za-z0-9_]{0,32}")) {
            warnings.add("storage.table-prefix '" + trimmed
                    + "' содержит недопустимые символы, использую 'snsocial_'.");
            return "snsocial_";
        }
        return trimmed;
    }

    private static int clamp(int value, int min, int max, List<String> warnings, String key) {
        if (value < min || value > max) {
            warnings.add(key + " = " + value + " вне диапазона " + min + ".." + max
                    + ", использую " + Math.max(min, Math.min(max, value)) + ".");
        }
        return Math.max(min, Math.min(max, value));
    }

    /** The JDBC URL this config describes; data.db lives in the plugin folder for SQLite. */
    String jdbcUrl(java.io.File dataFolder) {
        if ("mysql".equals(storageType)) {
            return "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                    + "?characterEncoding=utf8";
        }
        return "jdbc:sqlite:" + new java.io.File(dataFolder, "data.db").getAbsolutePath();
    }

    boolean isMysql() {
        return "mysql".equals(storageType);
    }
}
