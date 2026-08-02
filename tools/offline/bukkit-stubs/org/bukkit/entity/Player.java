// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.entity;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
public interface Player extends org.bukkit.command.CommandSender, org.bukkit.OfflinePlayer, HumanEntity {
    InventoryView openInventory(Inventory inventory);
    EntityScheduler getScheduler();
}
