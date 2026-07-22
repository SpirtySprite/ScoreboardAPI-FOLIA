package net.foliaboard.internal.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Wrapper over the region-thread scheduler API. Paper ships Folia's scheduler classes in
 * {@code paper-api}, so these calls compile against Paper and run on both Paper (main thread) and
 * Folia (region thread) without branching.
 * <p>
 * An entity's scheduler runs its tasks sequentially and follows the entity across regions; that is
 * what lets us keep a player's board thread-safe without locks.
 */
public final class Schedulers {
    private static final boolean FOLIA = detectFolia();

    private Schedulers() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /** @return {@code true} if running on a Folia (regionised) server. */
    public static boolean isFolia() {
        return FOLIA;
    }

    /** Test seam: when true, tasks run synchronously on the calling thread. Never set in production. */
    private static volatile boolean synchronousForTesting = false;

    /** Test-only: run all scheduled work inline so managers can be unit-tested without a server. */
    public static void setSynchronousForTesting(boolean value) {
        synchronousForTesting = value;
    }

    /**
     * Runs a repeating task tied to the global game state (weather, day/night, global tick counter).
     * On Folia this runs on the global region thread; on Paper, on the main thread.
     */
    public static @NotNull ScheduledHandle globalTimer(@NotNull Plugin plugin, @NotNull Runnable task,
                                                       long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) {
            return () -> {
            };
        }
        long delay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        var handle = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, scheduledTask -> task.run(), delay, period);
        return handle::cancel;
    }

    /** Runs a task off any game thread (Folia async scheduler). Used for user-supplied blocking work. */
    public static void async(@NotNull Plugin plugin, @NotNull Runnable task) {
        if (synchronousForTesting) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    /** Runs a one-shot task on the global region thread. */
    public static void global(@NotNull Plugin plugin, @NotNull Runnable task) {
        if (synchronousForTesting) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
    }

    /**
     * Runs a task on the given entity's thread. Tasks scheduled this way for the same entity never
     * run concurrently, which is exactly the invariant FoliaBoard uses to keep a player's board
     * thread-safe. If the entity has been removed the {@code retired} callback runs instead.
     *
     * @return {@code false} if the task could not be scheduled because the entity is already retired.
     */
    public static boolean onEntity(@NotNull Plugin plugin, @NotNull Entity entity,
                                   @NotNull Runnable task, @Nullable Runnable retired) {
        if (synchronousForTesting) {
            task.run();
            return true;
        }
        // Folia forbids scheduling once the plugin is disabled (e.g. during onDisable teardown).
        // In that case cleanup packets are pointless anyway, so quietly no-op instead of throwing.
        if (!plugin.isEnabled()) {
            return false;
        }
        try {
            return entity.getScheduler().run(plugin, scheduledTask -> task.run(),
                    retired == null ? null : retired) != null;
        } catch (IllegalPluginAccessException disabledMidCall) {
            return false;
        }
    }

    /** Convenience overload with no retired callback. */
    public static boolean onEntity(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable task) {
        return onEntity(plugin, entity, task, null);
    }

    /**
     * Schedules a repeating task on an entity's thread. Cancelling the returned handle stops it.
     * If the entity is already retired this returns a no-op handle.
     */
    public static @NotNull ScheduledHandle entityTimer(@NotNull Plugin plugin, @NotNull Entity entity,
                                                       @NotNull Consumer<ScheduledHandle> task,
                                                       long delayTicks, long periodTicks) {
        if (!plugin.isEnabled()) {
            return () -> {
            };
        }
        long delay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        try {
            var handle = entity.getScheduler().runAtFixedRate(plugin,
                    scheduledTask -> task.accept(scheduledTask::cancel), null, delay, period);
            if (handle == null) {
                return () -> {
                };
            }
            return handle::cancel;
        } catch (IllegalPluginAccessException disabledMidCall) {
            return () -> {
            };
        }
    }

    /** A cancellable scheduled task handle. */
    @FunctionalInterface
    public interface ScheduledHandle {
        void cancel();
    }
}
