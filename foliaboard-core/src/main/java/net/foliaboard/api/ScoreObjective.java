package net.foliaboard.api;

import net.kyori.adventure.text.ComponentLike;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A shared objective displayed either below players' names in the world or as numbers in the tab
 * list. Unlike a {@link Sidebar} (which is per-player content in the sidebar slot), this shows the
 * same scores to everyone -- e.g. a "level" number under every player's head.
 * <p>
 * Obtain one from {@code foliaBoard.belowName()} or {@code foliaBoard.tabList()}. Safe to call from
 * any thread.
 */
public interface ScoreObjective {

    /** Sets the objective's display name (shown after the number in the tab-list slot). */
    @NotNull ScoreObjective title(@NotNull ComponentLike title);

    /** Sets the number shown for a player (below their name / next to their tab entry). */
    @NotNull ScoreObjective score(@NotNull Player target, int value);

    /** Sets the number for an arbitrary entry (e.g. a fake name). */
    @NotNull ScoreObjective score(@NotNull String entry, int value);

    /** Removes an entry's number. */
    @NotNull ScoreObjective remove(@NotNull String entry);

    /**
     * Sets a number that only {@code viewer} sees for {@code entry}, overriding the shared value.
     * Lets you show, e.g., a different below-name number to different players.
     */
    @NotNull ScoreObjective scoreFor(@NotNull Player viewer, @NotNull String entry, int value);

    /** Removes a viewer-specific override, reverting {@code viewer} to the shared value (if any). */
    @NotNull ScoreObjective removeFor(@NotNull Player viewer, @NotNull String entry);

    /** Hides the whole objective (clears its display slot) for everyone. Reversible with {@link #show()}. */
    void hide();

    /** Re-shows the objective for everyone after {@link #hide()}, restoring its title and scores. */
    void show();

    /** @return whether the objective is currently hidden. */
    boolean hidden();
}
