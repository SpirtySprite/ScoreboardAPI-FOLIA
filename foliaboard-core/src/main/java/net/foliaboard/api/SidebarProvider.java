package net.foliaboard.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;

/**
 * Describes <i>what</i> a sidebar should show for a given player, leaving the <i>when</i> and
 * <i>how</i> to FoliaBoard. Register one with {@code foliaBoard.setGlobalSidebar(provider)} and
 * FoliaBoard will attach a sidebar to every player and refresh it on a fixed interval, per player,
 * on the correct thread.
 * <p>
 * Implementations should be cheap and side-effect free: they may be called frequently. This is the
 * natural home for placeholders and animation frames -- just compute the current component.
 */
public interface SidebarProvider {

    /** @return the title to show this player right now. */
    @NotNull Component title(@NotNull Player player);

    /** @return the lines to show this player right now; index 0 is the top line. */
    @NotNull List<Component> lines(@NotNull Player player);

    /**
     * @return whether this player should see a sidebar at all right now. Return {@code false} to
     * hide it (e.g. per-world or per-permission). Defaults to always visible.
     */
    default boolean visible(@NotNull Player player) {
        return true;
    }

    /**
     * How often, in server ticks, this provider is re-evaluated per player. 20 ticks = 1 second.
     * Lower is smoother but costs more; animations usually want 2-4.
     */
    default int refreshIntervalTicks() {
        return 20;
    }

    /**
     * Lambda-friendly factory. Lets you write a global board without implementing the interface:
     * <pre>{@code
     * board.setGlobalSidebar(SidebarProvider.of(
     *     p -> Text.mini("<aqua>MY SERVER"),
     *     p -> List.of(Text.mini("<gray>Online: " + Bukkit.getOnlinePlayers().size()))));
     * }</pre>
     */
    static @NotNull SidebarProvider of(@NotNull Function<Player, Component> title,
                                       @NotNull Function<Player, List<Component>> lines) {
        return of(title, lines, 20);
    }

    /** Lambda-friendly factory with a custom refresh interval (ticks). */
    static @NotNull SidebarProvider of(@NotNull Function<Player, Component> title,
                                       @NotNull Function<Player, List<Component>> lines,
                                       int refreshIntervalTicks) {
        return new SidebarProvider() {
            @Override
            public @NotNull Component title(@NotNull Player player) {
                return title.apply(player);
            }

            @Override
            public @NotNull List<Component> lines(@NotNull Player player) {
                return lines.apply(player);
            }

            @Override
            public int refreshIntervalTicks() {
                return refreshIntervalTicks;
            }
        };
    }
}
