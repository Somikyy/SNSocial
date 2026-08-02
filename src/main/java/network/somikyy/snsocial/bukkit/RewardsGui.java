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
import network.somikyy.snsocial.core.ClaimState;
import network.somikyy.snsocial.core.Network;
import network.somikyy.snsocial.core.PlayerLinks;
import network.somikyy.snsocial.core.RewardDef;
import network.somikyy.snsocial.core.RewardEngine;
import network.somikyy.snsocial.core.SubscriptionStatus;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * The rewards menu.
 *
 * <p>Deliberately free of {@code InventoryView}: in 1.21 it changed from an abstract class
 * to an interface, and bytecode compiled against 1.20 that calls its methods directly dies
 * with {@code IncompatibleClassChangeError} on newer servers (SPEC §3.6). Identification
 * goes through the {@link InventoryHolder} pattern, slots through
 * {@link InventoryClickEvent#getRawSlot()} - both stable across the 1.20.1-26.2 range.
 *
 * <p>Data loading and claiming run on the worker; the inventory is opened via the player's
 * {@code EntityScheduler}, which on plain Paper is simply the main thread.
 */
final class RewardsGui implements Listener {

    /** Marks our inventories and maps slots back to reward ids. */
    static final class Holder implements InventoryHolder {
        private final Map<Integer, String> slotToReward;
        private Inventory inventory;

        private Holder(Map<Integer, String> slotToReward) {
            this.slotToReward = slotToReward;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final org.bukkit.plugin.Plugin plugin;
    private final Texts texts;
    private final RewardService service;
    private final ExecutorService worker;
    /** Loads links+claims on the worker; kept as a lambda seam so the GUI has no storage code. */
    private final GuiData dataLoader;

    /** What the GUI needs to draw: one storage round-trip, fetched off-thread. */
    interface GuiData {
        Loaded load(UUID player) throws Exception;
    }

    record Loaded(PlayerLinks links, Map<String, ClaimState> claims,
                  Map<Network, SubscriptionStatus> statuses) {
    }

    RewardsGui(org.bukkit.plugin.Plugin plugin, Texts texts, RewardService service,
               ExecutorService worker, GuiData dataLoader) {
        this.plugin = plugin;
        this.texts = texts;
        this.service = service;
        this.worker = worker;
        this.dataLoader = dataLoader;
    }

    /** Builds and opens the menu; call from any thread. */
    void open(Player player) {
        UUID uuid = player.getUniqueId();
        worker.execute(() -> {
            Loaded data;
            try {
                data = dataLoader.load(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "GUI: не удалось загрузить данные игрока " + player.getName(), e);
                texts.send(player, "claim.storage-error");
                return;
            }
            Inventory inventory = build(data);
            // Opening an inventory must happen on the player's thread; EntityScheduler is
            // that thread on Folia and the main thread on plain Paper.
            player.getScheduler().run(plugin,
                    task -> player.openInventory(inventory), null);
        });
    }

    private Inventory build(Loaded data) {
        List<RewardDef> rewards = service.rewards();
        Map<Integer, String> slotMap = new HashMap<>();
        int maxSlot = 0;
        for (RewardDef def : rewards) {
            if (def.slot() >= 0) {
                maxSlot = Math.max(maxSlot, def.slot());
            }
        }
        int size = Math.min(54, Math.max(27, ((maxSlot + 2 + 8) / 9) * 9));

        Holder holder = new Holder(slotMap);
        Inventory inventory = Bukkit.createInventory(holder, size, texts.mm("gui.title"));
        holder.inventory = inventory;

        long now = System.currentTimeMillis();
        int autoSlot = 0;
        for (RewardDef def : rewards) {
            ClaimState claim = data.claims().getOrDefault(def.id(), ClaimState.fresh(def.id()));
            RewardEngine.Availability verdict = RewardEngine.availability(
                    def, data.links(), data.statuses(), claim, now);
            int slot = def.slot() >= 0 ? def.slot() : nextFree(inventory, autoSlot);
            // size-1 belongs to the info item: a reward there would be drawn over, yet its
            // slot mapping would survive - and clicking "Мои привязки" would claim it.
            if (slot < 0 || slot >= size - 1) {
                continue;
            }
            autoSlot = def.slot() >= 0 ? autoSlot : slot + 1;
            inventory.setItem(slot, rewardItem(def, verdict));
            slotMap.put(slot, def.id());
        }
        inventory.setItem(size - 1, infoItem(data));
        return inventory;
    }

    private int nextFree(Inventory inventory, int from) {
        for (int i = from; i < inventory.getSize() - 1; i++) {
            if (inventory.getItem(i) == null) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack rewardItem(RewardDef def, RewardEngine.Availability verdict) {
        Material material = Material.matchMaterial(def.icon());
        if (material == null || material.isAir()) {
            material = Material.CHEST;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(texts.mm("gui.item-name", "name", def.displayName()));
        List<Component> lore = new ArrayList<>();
        for (String line : def.description()) {
            lore.add(texts.mm("gui.item-lore-line", "line", line));
        }
        lore.add(Component.empty());
        lore.add(stateLine(verdict));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component stateLine(RewardEngine.Availability verdict) {
        return switch (verdict.state()) {
            case AVAILABLE -> texts.mm("gui.state.available");
            case ALREADY_CLAIMED -> texts.mm("gui.state.claimed");
            case NEED_LINK -> texts.mm("gui.state.need-link",
                    "networks", service.networkNames(verdict.missing()));
            case NEED_SUBSCRIBE -> texts.mm("gui.state.need-subscribe",
                    "networks", service.networkNames(verdict.missing()));
            case CHECK_FAILED -> texts.mm("gui.state.check-failed");
            case COOLDOWN -> texts.mm("gui.state.cooldown",
                    "time", texts.duration(verdict.remainingMillis()));
            case LOCKED -> texts.mm("gui.state.locked");
        };
    }

    private ItemStack infoItem(Loaded data) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(texts.mm("gui.info.name"));
        List<Component> lore = new ArrayList<>();
        for (Network network : Network.values()) {
            String linked = data.links().isLinked(network) ? "yes" : "no";
            SubscriptionStatus status =
                    data.statuses().getOrDefault(network, SubscriptionStatus.UNKNOWN);
            String subscribed = !data.links().isLinked(network) ? "no"
                    : switch (status) {
                        case SUBSCRIBED -> "yes";
                        case NOT_SUBSCRIBED -> "no";
                        case UNKNOWN -> "unknown";
                    };
            lore.add(texts.mm("gui.info.line",
                    "network", texts.raw("network." + network.id()),
                    "linked", texts.raw("word." + linked),
                    "subscribed", texts.raw("word." + subscribed)));
        }
        lore.add(Component.empty());
        lore.add(texts.mm("gui.info.hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        // Cancel everything, including shift-clicks from the player's own inventory:
        // a menu that leaks items is a dupe bug waiting for a screenshot.
        event.setCancelled(true);
        String rewardId = holder.slotToReward.get(event.getRawSlot());
        if (rewardId == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        RewardDef def = service.reward(rewardId);
        if (def == null) {
            return;
        }
        worker.execute(() -> {
            service.tryClaim(player, def);
            // Redraw so the item the player just clicked shows its new state.
            if (player.isOnline()) {
                open(player);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
