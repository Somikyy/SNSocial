// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.inventory.meta;
import java.util.List;
import net.kyori.adventure.text.Component;
public interface ItemMeta {
    void displayName(Component displayName);
    void lore(List<Component> lore);
}
