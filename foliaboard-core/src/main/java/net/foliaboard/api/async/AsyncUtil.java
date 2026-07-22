package net.foliaboard.api.async;

import net.foliaboard.internal.scheduler.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Folia-safe threading helpers for your own surrounding work (FoliaBoard's own calls are already
 * thread-safe). Typically: do a lookup off-thread, then hop to the player's thread to paint.
 *
 * <pre>{@code
 * AsyncUtil.async(plugin, () -> {
 *     int coins = database.loadCoins(uuid);
 *     AsyncUtil.onPlayer(plugin, player, () ->
 *         board.createBoard(player).line("Coins: " + coins).build());
 * });
 * }</pre>
 */
public final class AsyncUtil {
    private AsyncUtil() {
    }

    /** Runs work off any game thread (Folia async scheduler / Paper async pool). */
    public static void async(@NotNull Plugin plugin, @NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    /** Runs delayed async work. */
    public static void asyncLater(@NotNull Plugin plugin, @NotNull Runnable task, @NotNull Duration delay) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(),
                Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** Runs work on the given player's region thread (or main thread on Paper). */
    public static void onPlayer(@NotNull Plugin plugin, @NotNull Player player, @NotNull Runnable task) {
        Schedulers.onEntity(plugin, player, task);
    }

    /** Runs work on the global region thread (global game state). */
    public static void global(@NotNull Plugin plugin, @NotNull Runnable task) {
        Schedulers.global(plugin, task);
    }

    /** @return whether this server is Folia (regionised). */
    public static boolean isFolia() {
        return Schedulers.isFolia();
    }
}
