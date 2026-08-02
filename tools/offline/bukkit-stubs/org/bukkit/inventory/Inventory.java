// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.inventory;
public interface Inventory {
    int getSize();
    ItemStack getItem(int index);
    void setItem(int index, ItemStack item);
    InventoryHolder getHolder();
}
