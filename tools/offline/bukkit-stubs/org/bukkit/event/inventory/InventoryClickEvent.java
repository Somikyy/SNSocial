// Compile-only stub. NOT shipped in the jar: the server provides the real class.
// The real methods live up the InventoryEvent hierarchy; flattened here because javac
// writes the receiver's static type as the constant-pool owner either way.
package org.bukkit.event.inventory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
public class InventoryClickEvent {
    private InventoryClickEvent() { }
    public Inventory getInventory() { throw new UnsupportedOperationException("stub"); }
    public int getRawSlot() { throw new UnsupportedOperationException("stub"); }
    public HumanEntity getWhoClicked() { throw new UnsupportedOperationException("stub"); }
    public void setCancelled(boolean cancel) { throw new UnsupportedOperationException("stub"); }
}
