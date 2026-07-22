package net.foliaboard.api;

import net.foliaboard.api.format.NumberFormat;
import net.foliaboard.api.hook.LineProcessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A per-player sidebar.
 * <p>
 * Every method is safe to call from any thread. Changes are queued and applied on the owning
 * player's region thread in call order, so concurrent updates don't race. Text accepts Adventure
 * {@link ComponentLike}. Line 0 is the top line.
 */
public interface Sidebar {

    /** @return the player this sidebar belongs to. */
    @NotNull Player player();

    /** @return the current title (the last value set, whether or not it has flushed yet). */
    @NotNull Component title();

    /** @return an immutable snapshot of the current line texts, top to bottom. */
    @NotNull List<Component> lines();

    /** @return the current number of lines. */
    int lineCount();

    /** Sets the sidebar title (the highlighted top bar). */
    @NotNull Sidebar title(@NotNull ComponentLike title);

    /** Sets a single line. Index {@code 0} is the top line. Missing lines in between render blank. */
    @NotNull Sidebar line(int index, @NotNull ComponentLike text);

    /** Sets a single line together with a number format (e.g. hide or restyle its score number). */
    @NotNull Sidebar line(int index, @NotNull ComponentLike text, @NotNull NumberFormat numberFormat);

    /** Replaces every line at once. Index {@code 0} is the top line. */
    @NotNull Sidebar lines(@NotNull List<? extends ComponentLike> lines);

    /** Removes a single line, shifting nothing (other indices keep their position). */
    @NotNull Sidebar removeLine(int index);

    /** Removes all lines but keeps the title and the board visible. */
    @NotNull Sidebar clearLines();

    /**
     * Replaces this board's own line processors (applied after any global ones registered on
     * {@code FoliaBoard}). Pass an empty list to clear them. Unlike the global processors, these only
     * affect this one board.
     */
    @NotNull Sidebar lineProcessors(@NotNull List<LineProcessor> processors);

    /** Shows or hides the whole sidebar without discarding its content. */
    @NotNull Sidebar visible(boolean visible);

    /** @return whether the sidebar is currently shown. */
    boolean visible();

    /**
     * Permanently removes this sidebar for the player and frees its resources. After this the
     * instance must not be reused. FoliaBoard also closes sidebars automatically on quit.
     */
    void close();

    /** @return whether {@link #close()} has been called. */
    boolean closed();
}
