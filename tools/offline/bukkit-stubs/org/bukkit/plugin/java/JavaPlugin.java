// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.plugin.java;
import java.io.File;
import java.util.logging.Logger;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
    public void onEnable() { }
    public void onDisable() { }
    @Override public Logger getLogger() { throw new UnsupportedOperationException("stub"); }
    public File getDataFolder() { throw new UnsupportedOperationException("stub"); }
    public FileConfiguration getConfig() { throw new UnsupportedOperationException("stub"); }
    public void saveDefaultConfig() { throw new UnsupportedOperationException("stub"); }
    public void reloadConfig() { throw new UnsupportedOperationException("stub"); }
    public PluginCommand getCommand(String name) { throw new UnsupportedOperationException("stub"); }
}
