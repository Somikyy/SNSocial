// Compile-only stub. NOT shipped in the jar: the server provides the real class.
// Only the constants SNSocial names in code are declared; config icons resolve at
// runtime through matchMaterial and never need constants here.
package org.bukkit;
public enum Material {
    CHEST,
    BOOK;
    public static Material matchMaterial(String name) { throw new UnsupportedOperationException("stub"); }
    public boolean isAir() { throw new UnsupportedOperationException("stub"); }
}
