// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.command;
public interface CommandExecutor {
    boolean onCommand(CommandSender sender, Command command, String label, String[] args);
}
