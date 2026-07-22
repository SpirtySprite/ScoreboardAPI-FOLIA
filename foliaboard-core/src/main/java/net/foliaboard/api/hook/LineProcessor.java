package net.foliaboard.api.hook;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A hook that can inspect or rewrite every piece of sidebar text just before it is sent, for any
 * board (manual, builder, provider, or layout). Register with {@code foliaBoard.addLineProcessor(...)}.
 * <p>
 * Runs on the viewing player's region thread. Keep it fast. Return the (possibly modified) component;
 * return the input unchanged to pass through.
 *
 * <pre>{@code
 * // Example: force every line through a global prefix.
 * foliaBoard.addLineProcessor((player, index, line) ->
 *     index == TITLE ? line : Component.text("» ").append(line));
 * }</pre>
 */
@FunctionalInterface
public interface LineProcessor {
    /** The {@code index} value passed for the sidebar title (as opposed to a numbered line). */
    int TITLE = -1;

    @NotNull Component process(@NotNull Player player, int index, @NotNull Component line);
}
