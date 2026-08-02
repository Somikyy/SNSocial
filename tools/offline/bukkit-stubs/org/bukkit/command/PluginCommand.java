// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package org.bukkit.command;
public class PluginCommand extends Command {
    private PluginCommand() { }
    public void setExecutor(CommandExecutor executor) { throw new UnsupportedOperationException("stub"); }
    public void setTabCompleter(TabCompleter completer) { throw new UnsupportedOperationException("stub"); }
}
