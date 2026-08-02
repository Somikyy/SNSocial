// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.configuration;
import java.util.List;
import java.util.Set;
public interface ConfigurationSection {
    Set<String> getKeys(boolean deep);
    String getString(String path);
    String getString(String path, String def);
    int getInt(String path, int def);
    long getLong(String path, long def);
    boolean getBoolean(String path, boolean def);
    List<String> getStringList(String path);
    ConfigurationSection getConfigurationSection(String path);
}
