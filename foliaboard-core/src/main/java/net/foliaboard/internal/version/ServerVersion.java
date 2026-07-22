package net.foliaboard.internal.version;

import org.bukkit.Bukkit;

/**
 * Parses the running server's Minecraft version into a comparable data-version-ish tuple.
 * FoliaBoard targets modern Paper/Folia (1.20.6+) where Mojang mappings are used at runtime and
 * the modern scoreboard packets (per-score display component + number format) are available.
 */
public final class ServerVersion {
    private final int major;
    private final int minor;
    private final int patch;

    private ServerVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static ServerVersion current() {
        // Bukkit.getMinecraftVersion() exists on Paper and returns e.g. "1.21.4".
        String raw;
        try {
            raw = Bukkit.getMinecraftVersion();
        } catch (Throwable t) {
            // Fallback: parse from Bukkit.getBukkitVersion() -> "1.21.4-R0.1-SNAPSHOT"
            raw = Bukkit.getBukkitVersion();
        }
        return parse(raw);
    }

    static ServerVersion parse(String raw) {
        String cleaned = raw.split("-")[0].trim();
        String[] parts = cleaned.split("\\.");
        int major = safe(parts, 0);
        int minor = safe(parts, 1);
        int patch = safe(parts, 2);
        return new ServerVersion(major, minor, patch);
    }

    private static int safe(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return true if this version is >= the given minor/patch of Minecraft 1.x. */
    public boolean isAtLeast(int minor, int patch) {
        if (this.minor != minor) {
            return this.minor > minor;
        }
        return this.patch >= patch;
    }

    /** 1.20.3+ introduced per-score display components and number formats. */
    public boolean supportsScoreDisplayName() {
        return isAtLeast(20, 3);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
