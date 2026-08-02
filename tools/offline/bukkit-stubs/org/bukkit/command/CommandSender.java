// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.command;
public interface CommandSender {
    void sendMessage(net.kyori.adventure.text.Component message);
    boolean hasPermission(String name);
}
