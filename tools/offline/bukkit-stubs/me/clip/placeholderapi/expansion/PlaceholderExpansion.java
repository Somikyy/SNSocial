// Compile-only stub. NOT shipped in the jar: the PlaceholderAPI plugin provides the
// real class, and SNSocial touches it only after checking PAPI is installed.
package me.clip.placeholderapi.expansion;
import org.bukkit.OfflinePlayer;
public abstract class PlaceholderExpansion {
    public abstract String getIdentifier();
    public abstract String getAuthor();
    public abstract String getVersion();
    public boolean persist() { return false; }
    public boolean register() { throw new UnsupportedOperationException("stub"); }
    public boolean unregister() { throw new UnsupportedOperationException("stub"); }
    public String onRequest(OfflinePlayer player, String params) { return null; }
}
