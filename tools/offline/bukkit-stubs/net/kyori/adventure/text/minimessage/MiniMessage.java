// Compile-only stub. NOT shipped in the jar: the server provides the real class.
package net.kyori.adventure.text.minimessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
public interface MiniMessage extends ComponentSerializer<Component, Component, String> {
    static MiniMessage miniMessage() { throw new UnsupportedOperationException("stub"); }
}
