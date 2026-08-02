// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit;
import java.util.Collection;
import java.util.UUID;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.PluginManager;
public final class Bukkit {
    private Bukkit() { }
    public static Player getPlayer(UUID id) { throw new UnsupportedOperationException("stub"); }
    public static Player getPlayerExact(String name) { throw new UnsupportedOperationException("stub"); }
    public static OfflinePlayer getOfflinePlayerIfCached(String name) { throw new UnsupportedOperationException("stub"); }
    public static boolean getOnlineMode() { throw new UnsupportedOperationException("stub"); }
    public static Collection<? extends Player> getOnlinePlayers() { throw new UnsupportedOperationException("stub"); }
    public static Inventory createInventory(InventoryHolder owner, int size, net.kyori.adventure.text.Component title) { throw new UnsupportedOperationException("stub"); }
    public static GlobalRegionScheduler getGlobalRegionScheduler() { throw new UnsupportedOperationException("stub"); }
    public static PluginManager getPluginManager() { throw new UnsupportedOperationException("stub"); }
    public static ConsoleCommandSender getConsoleSender() { throw new UnsupportedOperationException("stub"); }
    public static boolean dispatchCommand(CommandSender sender, String commandLine) { throw new UnsupportedOperationException("stub"); }
}
