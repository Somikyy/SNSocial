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

import network.somikyy.snsocial.core.ClaimState;
import network.somikyy.snsocial.core.FmSocialRewardImport;
import network.somikyy.snsocial.core.JavaHttpTransport;
import network.somikyy.snsocial.core.LinkCodeService;
import network.somikyy.snsocial.core.LinkService;
import network.somikyy.snsocial.core.Messages;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.RewardDef;
import network.somikyy.snsocial.core.SqlStorage;
import network.somikyy.snsocial.core.StatusCache;
import network.somikyy.snsocial.core.Storage;
import network.somikyy.snsocial.core.TelegramApi;
import network.somikyy.snsocial.core.Version;
import network.somikyy.snsocial.core.VkApi;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Wiring and lifecycle. All actual behaviour lives in core/ and in the service classes;
 * this class builds them, connects them and takes them apart again.
 *
 * <p>{@code /snsocial reload} is a full soft-restart - stop(), re-read config, start() -
 * because a "reload" that silently keeps the old bot token connected is a support ticket
 * with extra steps. The pollers, worker, storage and listeners are all rebuilt.
 */
public final class SNSocialPlugin extends JavaPlugin {

    private SNSocialConfig cfg;
    private Texts texts;
    private ScheduledExecutorService worker;
    private Storage storage;
    private StatusCache cache;
    private LinkCodeService codes;
    private RewardService service;
    private RewardsGui gui;
    private PlaceholderData placeholderData;
    private JavaHttpTransport transport;

    private TelegramApi telegramApi;
    private volatile String telegramBotUsername;
    private VkApi vkApi;

    private TelegramPoller telegramPoller;
    private Thread telegramThread;
    private VkPoller vkPoller;
    private Thread vkThread;
    private Object papiExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start(null);
    }

    @Override
    public void onDisable() {
        stop();
    }

    void reloadEverything(CommandSender feedback) {
        stop();
        reloadConfig();
        start(feedback);
    }

    // --------------------------------------------------------------------------- lifecycle

    private void start(CommandSender feedback) {
        cfg = SNSocialConfig.from(getConfig());
        for (String warning : cfg.warnings()) {
            getLogger().warning(warning);
        }
        texts = new Texts(Messages.load(
                new File(getDataFolder(), "messages-ru.txt").toPath(),
                new File(getDataFolder(), "messages-en.txt").toPath()), cfg.russian());
        cache = new StatusCache();
        codes = new LinkCodeService(cfg.codeTtlMinutes() * 60_000L);
        placeholderData = new PlaceholderData();
        transport = new JavaHttpTransport();

        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "SNSocial-worker");
            thread.setDaemon(true);
            return thread;
        });

        SqlStorage sql = new SqlStorage(
                cfg.jdbcUrl(getDataFolder()),
                cfg.isMysql() ? cfg.mysqlUser() : null,
                cfg.isMysql() ? cfg.mysqlPassword() : null,
                cfg.tablePrefix());
        storage = sql;
        // First task in the worker queue: everything else that touches storage is queued
        // behind it, so "not initialized yet" cannot be observed from inside the plugin.
        worker.execute(() -> {
            try {
                sql.init();
                getLogger().info("Хранилище готово: " + cfg.storageType() + ".");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Не удалось открыть хранилище ("
                        + cfg.storageType() + "). Плагин выключается.", e);
                Bukkit.getGlobalRegionScheduler().execute(this,
                        () -> Bukkit.getPluginManager().disablePlugin(this));
            }
        });

        boolean telegramOn = cfg.telegramEnabled() && !cfg.telegramToken().isEmpty()
                && !cfg.telegramChannel().isEmpty();
        if (cfg.telegramEnabled() && !telegramOn) {
            getLogger().warning("Telegram включён, но bot-token или channel пустые — "
                    + "привязка Telegram работать не будет.");
        }
        boolean vkOn = cfg.vkEnabled() && !cfg.vkToken().isEmpty() && cfg.vkGroupId() > 0;
        if (cfg.vkEnabled() && !vkOn) {
            getLogger().warning("VK включён, но group-token или group-id пустые — "
                    + "привязка VK работать не будет.");
        }
        if (!telegramOn && !vkOn) {
            getLogger().warning("Ни одна соцсеть не настроена: заполни секции telegram/vk "
                    + "в config.yml. Сейчас плагин может показывать только пустой GUI.");
        }

        telegramApi = telegramOn ? new TelegramApi(transport, cfg.telegramToken()) : null;
        vkApi = vkOn ? new VkApi(transport, cfg.vkApiUrl(), cfg.vkToken(), cfg.vkGroupId())
                     : null;
        telegramBotUsername = null;

        LinkService linkService = new LinkService(codes, storage);
        service = new RewardService(this, texts, storage, cache, telegramApi,
                cfg.telegramChannel(), vkApi, cfg.rewards(), placeholderData);
        gui = new RewardsGui(this, texts, service, worker, uuid -> {
            PlayerLinks links = storage.links(uuid);
            placeholderData.putLinks(links);
            return new RewardsGui.Loaded(links, storage.claims(uuid),
                    service.statuses(links, false));
        });

        if (telegramApi != null) {
            worker.execute(() -> {
                telegramBotUsername = telegramApi.fetchBotUsername();
                if (telegramBotUsername == null) {
                    getLogger().warning("Telegram: getMe не ответил — проверь bot-token. "
                            + "Повторная попытка при следующем /snsocial link telegram.");
                } else {
                    getLogger().info("Telegram-бот: @" + telegramBotUsername);
                }
            });
            telegramPoller = new TelegramPoller(getLogger(), texts, telegramApi, linkService,
                    cfg.codeTtlMinutes() * 60_000L, this::onLinked);
            telegramThread = daemon(telegramPoller, "SNSocial-telegram-poller");
        }
        if (vkApi != null) {
            vkPoller = new VkPoller(getLogger(), texts, vkApi, linkService, this::onLinked);
            vkThread = daemon(vkPoller, "SNSocial-vk-poller");
        }

        Bukkit.getPluginManager().registerEvents(gui, this);
        if (cfg.checkOnJoin()) {
            Bukkit.getPluginManager().registerEvents(
                    new JoinListener(this, worker, cfg.joinDelaySeconds()), this);
        }

        PluginCommand command = getCommand("snsocial");
        if (command != null) {
            SNSocialCommand executor = new SNSocialCommand(this, texts);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        worker.scheduleWithFixedDelay(service::recheckAll,
                cfg.checkIntervalMinutes(), cfg.checkIntervalMinutes(), TimeUnit.MINUTES);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPapi();
        }
        if (cfg.updateCheck()) {
            UpdateCheck.run(getLogger(), texts, transport, worker);
        }

        getLogger().info("SNSocial " + Version.VERSION + " готов. Награды: "
                + cfg.rewards().size() + ", перепроверка каждые "
                + cfg.checkIntervalMinutes() + " мин.");
        getLogger().info("Линейка SN — бесплатные плагины с открытым кодом: t.me/somikyy");
        if (feedback != null) {
            texts.send(feedback, "admin.reload.done");
        }
    }

    private void stop() {
        // Listeners go first: a player joining between the worker shutdown below and a
        // later unregister would schedule onto a dead executor and stack-trace into the
        // event bus for nothing.
        HandlerList.unregisterAll(this);
        if (telegramPoller != null) {
            telegramPoller.stop();
        }
        if (vkPoller != null) {
            vkPoller.stop();
        }
        interrupt(telegramThread);
        interrupt(vkThread);
        telegramPoller = null;
        vkPoller = null;
        telegramThread = null;
        vkThread = null;

        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                    getLogger().warning("Рабочий поток не остановился за 3 секунды.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Закрытие хранилища не удалось", e);
            }
            storage = null;
        }
        unregisterPapi();
    }

    /** After a successful link: greet in game, then re-check so auto-claims fire instantly. */
    private void onLinked(UUID player, String name) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            texts.send(online, "link.success.game");
        }
        try {
            worker.execute(() -> {
                try {
                    service.recheck(storage.links(player), name);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING,
                            "Проверка после привязки не удалась: " + name, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A link raced the shutdown; the join re-check will pick the player up.
        }
    }

    // ------------------------------------------------------------------------------ import

    /** Runs on the worker. See SPEC §6.3 for the fmSocialReward on-disk format. */
    void importFmSocialReward(CommandSender sender, String rewardId) {
        File pluginsDir = getDataFolder().getParentFile();
        File source = new File(pluginsDir, "fmSocialReward/config.yml");
        if (!source.isFile()) {
            source = new File(pluginsDir, "fmSocialRewards/config.yml");
        }
        if (!source.isFile()) {
            texts.send(sender, "admin.import.not-found");
            return;
        }
        RewardDef target = rewardId != null ? service.reward(rewardId) : null;
        if (target == null) {
            for (RewardDef def : service.rewards()) {
                if (def.type() == RewardDef.Type.SUBSCRIBE) {
                    target = def;
                    break;
                }
            }
        }
        if (target == null) {
            texts.send(sender, "admin.import.no-reward");
            return;
        }
        int imported = 0;
        int already = 0;
        int skipped = 0;
        try {
            String content = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            for (String nick : FmSocialRewardImport.parseNicknames(content)) {
                UUID uuid = SNSocialCommand.resolvePlayer(nick);
                if (uuid == null) {
                    skipped++;
                    continue;
                }
                ClaimState claim = storage.claim(uuid, target.id());
                if (claim.everClaimed()) {
                    already++;
                    continue;
                }
                storage.putClaim(uuid, claim.afterClaim(System.currentTimeMillis()));
                imported++;
            }
            texts.send(sender, "admin.import.done",
                    "imported", String.valueOf(imported),
                    "already", String.valueOf(already),
                    "skipped", String.valueOf(skipped),
                    "reward", target.id());
            getLogger().info("Импорт из fmSocialReward: перенесено " + imported
                    + ", уже были " + already + ", не распознано " + skipped + ".");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Импорт из fmSocialReward не удался", e);
            texts.send(sender, "admin.import.failed");
        }
    }

    // --------------------------------------------------------------------------- accessors

    ScheduledExecutorService worker() {
        return worker;
    }

    Storage storage() {
        return storage;
    }

    StatusCache cache() {
        return cache;
    }

    LinkCodeService codes() {
        return codes;
    }

    RewardService service() {
        return service;
    }

    RewardsGui gui() {
        return gui;
    }

    PlaceholderData placeholders() {
        return placeholderData;
    }

    String telegramBotUsername() {
        String cached = telegramBotUsername;
        if (cached == null && telegramApi != null) {
            // getMe failed at startup (network hiccup, token added later); retry lazily
            // from the worker so the next /link attempt can succeed without a reload.
            worker.execute(() -> telegramBotUsername = telegramApi.fetchBotUsername());
        }
        return cached;
    }

    long vkGroupId() {
        return cfg.vkGroupId();
    }

    boolean networkEnabled(Network network) {
        return network == Network.TELEGRAM ? telegramApi != null : vkApi != null;
    }

    // ----------------------------------------------------------------------------- helpers

    private Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Separate methods so the me.clip classes never load when PAPI is absent. */
    private void registerPapi() {
        try {
            PapiExpansion expansion = new PapiExpansion(this, texts);
            expansion.register();
            papiExpansion = expansion;
            getLogger().info("PlaceholderAPI найден: плейсхолдеры %snsocial_*% активны.");
        } catch (LinkageError | RuntimeException e) {
            getLogger().warning("PlaceholderAPI найден, но регистрация не удалась: " + e);
        }
    }

    private void unregisterPapi() {
        if (papiExpansion instanceof PapiExpansion expansion) {
            try {
                expansion.unregister();
            } catch (LinkageError | RuntimeException ignored) {
                // PAPI may already be gone during shutdown; nothing to clean then.
            }
            papiExpansion = null;
        }
    }
}
