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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC {@link Storage} over SQLite or MySQL.
 *
 * <p>The drivers come from the server, not from us: Paper bundles sqlite-jdbc and
 * mysql-connector-j (1.20.1: 3.42.0.0 / 8.0.33; 26.x: 3.49.1.0 / 9.2.0) and its docs say
 * plugins need not shade them - docs.papermc.io/paper/dev/using-databases. That keeps the
 * zero-dependency rule intact. Paper does NOT bundle HikariCP, so there is no pool: one
 * connection, one worker thread, reconnect on failure. For link-and-claim traffic that is
 * plenty, and it is the whole class simpler.
 *
 * <p>SQL stays inside the SQLite∩MySQL intersection - the only dialect-sensitive statement
 * is the upsert, and {@code REPLACE INTO} exists in both. Uniqueness of a social account per
 * network is enforced twice: by a SELECT under this object's lock (which also produces the
 * conflicting player's name for the error message) and by UNIQUE constraints as the backstop.
 *
 * <p>Everything here runs on the worker thread; the main server thread never touches JDBC.
 */
public final class SqlStorage implements Storage {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String players;
    private final String claims;

    private Connection connection;

    /**
     * @param jdbcUrl     e.g. {@code jdbc:sqlite:plugins/SNSocial/data.db} or
     *                    {@code jdbc:mysql://host:3306/db?characterEncoding=utf8}
     * @param user        MySQL user; ignored by SQLite (pass null)
     * @param password    MySQL password; ignored by SQLite (pass null)
     * @param tablePrefix prefix for both tables, e.g. {@code snsocial_}
     */
    public SqlStorage(String jdbcUrl, String user, String password, String tablePrefix) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.players = tablePrefix + "players";
        this.claims = tablePrefix + "claims";
    }

    /** Connects and creates tables. Fail here is fatal for the plugin - better now than mid-claim. */
    public synchronized void init() throws SQLException {
        // The drivers register themselves via ServiceLoader, but that discovery can miss
        // drivers living in the server's classloader when the caller is a plugin classloader.
        // Loading the classes by name is harmless when redundant and decisive when not.
        tryLoadDriver("org.sqlite.JDBC");
        tryLoadDriver("com.mysql.cj.jdbc.Driver");
        ensureConnection();
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players + " ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(16),"
                    + "telegram_id BIGINT NULL,"
                    + "vk_id BIGINT NULL,"
                    + "telegram_linked_at BIGINT NOT NULL DEFAULT 0,"
                    + "vk_linked_at BIGINT NOT NULL DEFAULT 0,"
                    + "UNIQUE (telegram_id),"
                    + "UNIQUE (vk_id))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + claims + " ("
                    + "uuid VARCHAR(36) NOT NULL,"
                    + "reward_id VARCHAR(64) NOT NULL,"
                    + "times_claimed INT NOT NULL,"
                    + "last_claimed_at BIGINT NOT NULL,"
                    + "revoked_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, reward_id))");
        }
    }

    @Override
    public synchronized PlayerLinks links(UUID player) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT telegram_id, vk_id FROM " + players + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return PlayerLinks.none(player);
                }
                return new PlayerLinks(player, readNullableLong(rs, 1), readNullableLong(rs, 2));
            }
        }
    }

    @Override
    public synchronized void link(UUID player, String playerName, Network network,
                                  long socialId, long now) throws LinkConflict, SQLException {
        ensureConnection();
        String column = network == Network.TELEGRAM ? "telegram_id" : "vk_id";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, name FROM " + players + " WHERE " + column + " = ?")) {
            ps.setLong(1, socialId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !rs.getString(1).equals(player.toString())) {
                    String name = rs.getString(2);
                    throw new LinkConflict(name != null ? name : rs.getString(1));
                }
            }
        }
        Row current = readRow(player);
        Row updated = network == Network.TELEGRAM
                ? new Row(playerName, socialId, current.vkId(), now, current.vkLinkedAt())
                : new Row(playerName, current.telegramId(), socialId,
                          current.telegramLinkedAt(), now);
        writeRow(player, updated);
    }

    @Override
    public synchronized void unlink(UUID player, Network network) throws SQLException {
        ensureConnection();
        Row current = readRow(player);
        Row updated = network == Network.TELEGRAM
                ? new Row(current.name(), null, current.vkId(), 0, current.vkLinkedAt())
                : new Row(current.name(), current.telegramId(), null,
                          current.telegramLinkedAt(), 0);
        writeRow(player, updated);
    }

    @Override
    public synchronized Optional<UUID> playerBySocialId(Network network, long socialId)
            throws SQLException {
        ensureConnection();
        String column = network == Network.TELEGRAM ? "telegram_id" : "vk_id";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM " + players + " WHERE " + column + " = ?")) {
            ps.setLong(1, socialId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1)))
                                 : Optional.empty();
            }
        }
    }

    @Override
    public synchronized ClaimState claim(UUID player, String rewardId) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT times_claimed, last_claimed_at, revoked_at FROM " + claims
                        + " WHERE uuid = ? AND reward_id = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, rewardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ClaimState.fresh(rewardId);
                }
                return new ClaimState(rewardId, rs.getInt(1), rs.getLong(2), rs.getLong(3));
            }
        }
    }

    @Override
    public synchronized Map<String, ClaimState> claims(UUID player) throws SQLException {
        ensureConnection();
        Map<String, ClaimState> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reward_id, times_claimed, last_claimed_at, revoked_at FROM " + claims
                        + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    out.put(id, new ClaimState(id, rs.getInt(2), rs.getLong(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void putClaim(UUID player, ClaimState state) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO " + claims
                        + " (uuid, reward_id, times_claimed, last_claimed_at, revoked_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, state.rewardId());
            ps.setInt(3, state.timesClaimed());
            ps.setLong(4, state.lastClaimedAt());
            ps.setLong(5, state.revokedAt());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<PlayerLinks> allLinked() throws SQLException {
        ensureConnection();
        List<PlayerLinks> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, telegram_id, vk_id FROM " + players
                     + " WHERE telegram_id IS NOT NULL OR vk_id IS NOT NULL")) {
            while (rs.next()) {
                out.add(new PlayerLinks(UUID.fromString(rs.getString(1)),
                        readNullableLong(rs, 2), readNullableLong(rs, 3)));
            }
        }
        return out;
    }

    @Override
    public synchronized Optional<String> playerName(UUID player) throws SQLException {
        ensureConnection();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM " + players + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Shutting down; there is nobody left to tell.
            }
            connection = null;
        }
    }

    // ---------------------------------------------------------------------------- internals

    /** Full player row - REPLACE INTO rewrites whole rows, so reads must be whole too. */
    private record Row(String name, Long telegramId, Long vkId,
                       long telegramLinkedAt, long vkLinkedAt) {
    }

    private Row readRow(UUID player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, telegram_id, vk_id, telegram_linked_at, vk_linked_at FROM "
                        + players + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Row(null, null, null, 0, 0);
                }
                return new Row(rs.getString(1), readNullableLong(rs, 2),
                        readNullableLong(rs, 3), rs.getLong(4), rs.getLong(5));
            }
        }
    }

    private void writeRow(UUID player, Row row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO " + players
                        + " (uuid, name, telegram_id, vk_id, telegram_linked_at, vk_linked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, row.name());
            setNullableLong(ps, 3, row.telegramId());
            setNullableLong(ps, 4, row.vkId());
            ps.setLong(5, row.telegramLinkedAt());
            ps.setLong(6, row.vkLinkedAt());
            ps.executeUpdate();
        }
    }

    private void ensureConnection() throws SQLException {
        boolean stale;
        try {
            stale = connection == null || connection.isClosed() || !connection.isValid(2);
        } catch (SQLException e) {
            stale = true;
        }
        if (stale) {
            close();
            connection = user == null
                    ? DriverManager.getConnection(jdbcUrl)
                    : DriverManager.getConnection(jdbcUrl, user, password);
        }
    }

    private static void tryLoadDriver(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            // Only one of the two drivers is needed; the other may legitimately be absent.
        }
    }

    private static Long readNullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
