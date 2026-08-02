// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.plugin;
public interface PluginManager {
    void registerEvents(org.bukkit.event.Listener listener, Plugin plugin);
    Plugin getPlugin(String name);
    void disablePlugin(Plugin plugin);
}
