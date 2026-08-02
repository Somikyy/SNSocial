// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package io.papermc.paper.threadedregions.scheduler;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
public interface EntityScheduler {
    ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired);
}
