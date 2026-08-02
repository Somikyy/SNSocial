// Compile-only stub. NOT shipped in the jar: the server provides the real class.
// The generic shape must match the real interface exactly: deserialize() reaches our
// bytecode through erasure, and a stub declaring deserialize(String) directly would
// emit a descriptor the real API does not have - NoSuchMethodError on a live server.
package net.kyori.adventure.text.serializer;
import net.kyori.adventure.text.Component;
public interface ComponentSerializer<I extends Component, O extends Component, R> {
    O deserialize(R input);
}
