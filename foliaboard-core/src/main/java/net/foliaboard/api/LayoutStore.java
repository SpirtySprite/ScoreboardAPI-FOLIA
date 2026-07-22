package net.foliaboard.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional persistence for "which layout was a player last on". Register one with
 * {@code foliaBoard.setLayoutStore(...)} and FoliaBoard will remember a player's layout when it's
 * applied and re-apply it automatically on their next join - no join-listener glue on your side.
 *
 * <p>Back it with whatever you like (a map, a config, a database, a storage API). FoliaBoard invokes
 * both methods off the region thread, so a blocking database call here can't stall a player's tick.
 */
public interface LayoutStore {

    /** Persist that {@code player} is now on {@code layoutName}. Invoked off the region thread. */
    @NotNull CompletableFuture<Void> remember(@NotNull UUID player, @NotNull String layoutName);

    /** @return a future of the player's last layout name, or {@code null} if none is remembered. */
    @NotNull CompletableFuture<@Nullable String> lastLayout(@NotNull UUID player);
}
