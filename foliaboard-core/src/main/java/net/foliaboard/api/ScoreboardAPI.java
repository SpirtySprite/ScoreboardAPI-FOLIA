package net.foliaboard.api;

import net.foliaboard.FoliaBoard;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Static wrapper around a single {@link FoliaBoard} instance, for code that prefers a global handle.
 * Call {@link #init(Plugin)} in {@code onEnable} and {@link #shutdown()} in {@code onDisable}; then
 * {@code ScoreboardAPI.get()} (or the delegate methods) from anywhere.
 * <p>
 * If you need multiple instances or want to manage the lifecycle yourself, use
 * {@link FoliaBoard#create(Plugin)} instead.
 */
public final class ScoreboardAPI {
    private static volatile FoliaBoard instance;

    private ScoreboardAPI() {
    }

    /** Initialises the global instance. Call once in {@code onEnable}. */
    public static @NotNull FoliaBoard init(@NotNull Plugin plugin) {
        FoliaBoard board = FoliaBoard.create(plugin);
        instance = board;
        return board;
    }

    /** @return the global instance. Throws if {@link #init(Plugin)} has not been called. */
    public static @NotNull FoliaBoard get() {
        FoliaBoard local = instance;
        if (local == null) {
            throw new IllegalStateException("ScoreboardAPI.init(plugin) has not been called yet");
        }
        return local;
    }

    public static boolean isInitialised() {
        return instance != null;
    }

    /** Closes and clears the global instance. Call in {@code onDisable}. */
    public static void shutdown() {
        FoliaBoard local = instance;
        if (local != null) {
            local.close();
            instance = null;
        }
    }

    // -- convenience delegates so you can write ScoreboardAPI.createBoard(player) directly --

    public static @NotNull BoardBuilder createBoard(@NotNull Player player) {
        return get().createBoard(player);
    }

    public static @NotNull NametagBuilder createNametag(@NotNull Player player) {
        return get().createNametag(player);
    }

    public static @NotNull Sidebar sidebar(@NotNull Player player) {
        return get().sidebar(player);
    }
}
