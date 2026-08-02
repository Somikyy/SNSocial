// Compile-only stub. NOT shipped in the jar: the server provides the real class.
// Exists because Component.empty() returns TextComponent, and the static-method
// descriptor must match the real API byte for byte (caught by CI on 2026-08-02).
package net.kyori.adventure.text;
public interface TextComponent extends Component {
}
