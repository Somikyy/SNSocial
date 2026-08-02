// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit;
import java.util.UUID;
public interface OfflinePlayer {
    UUID getUniqueId();
    String getName();
    boolean isOnline();
}
