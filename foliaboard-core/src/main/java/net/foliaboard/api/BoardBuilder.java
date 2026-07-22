package net.foliaboard.api;

import net.foliaboard.FoliaBoard;
import net.foliaboard.api.animation.Animation;
import net.foliaboard.api.format.NumberFormat;
import net.foliaboard.api.text.Text;
import net.foliaboard.internal.scheduler.Schedulers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.TreeMap;
import java.util.function.Function;

/**
 * Fluent builder for a per-player sidebar, obtained from {@code foliaBoard.createBoard(player)}.
 * String arguments are parsed as MiniMessage.
 *
 * <pre>{@code
 * foliaBoard.createBoard(player)
 *     .placeholders(true)
 *     .title("<aqua>My Server")
 *     .line("<gray>Player: <white>%player%")
 *     .line("<gray>Online: <green>%online%")
 *     .build();
 * }</pre>
 *
 * A board with a placeholder string, an {@link Animation}, or a supplier line is refreshed
 * automatically on the player's thread; a fully static board is painted once.
 */
public final class BoardBuilder {
    private record LineSpec(Function<Player, Component> renderer, NumberFormat format, boolean dynamic) {
    }

    private final FoliaBoard board;
    private final Player player;

    private Function<Player, Component> titleRenderer = p -> Component.empty();
    private boolean titleDynamic = false;
    private final TreeMap<Integer, LineSpec> lines = new TreeMap<>();
    private final java.util.List<net.foliaboard.api.hook.LineProcessor> processors = new java.util.ArrayList<>();
    private int nextAutoIndex = 0;
    private boolean parsePlaceholders = false;
    private int refreshTicks = -1;

    public BoardBuilder(@NotNull FoliaBoard board, @NotNull Player player) {
        this.board = board;
        this.player = player;
    }

    /** Enables {@code %placeholder%} resolution (built-ins + PlaceholderAPI) on every string. */
    public @NotNull BoardBuilder placeholders(boolean enabled) {
        this.parsePlaceholders = enabled;
        return this;
    }

    /** Overrides the auto-refresh interval (ticks) for dynamic content. Default ~3 ticks. */
    public @NotNull BoardBuilder refreshEvery(int ticks) {
        this.refreshTicks = Math.max(1, ticks);
        return this;
    }

    /** Adds a line processor for this board only, applied after any global processors. */
    public @NotNull BoardBuilder processor(@NotNull net.foliaboard.api.hook.LineProcessor processor) {
        this.processors.add(processor);
        return this;
    }

    // ---- title ------------------------------------------------------------------------------

    public @NotNull BoardBuilder title(@NotNull String miniMessage) {
        Rendered r = render(miniMessage);
        this.titleRenderer = r.renderer;
        this.titleDynamic = r.dynamic;
        return this;
    }

    public @NotNull BoardBuilder title(@NotNull ComponentLike title) {
        Component c = title.asComponent();
        this.titleRenderer = p -> c;
        this.titleDynamic = false;
        return this;
    }

    public @NotNull BoardBuilder title(@NotNull Animation<Component> animation) {
        this.titleRenderer = p -> animation.current();
        this.titleDynamic = true;
        return this;
    }

    // ---- lines ------------------------------------------------------------------------------

    public @NotNull BoardBuilder line(int index, @NotNull String miniMessage) {
        Rendered r = render(miniMessage);
        lines.put(index, new LineSpec(r.renderer, null, r.dynamic));
        return this;
    }

    public @NotNull BoardBuilder line(int index, @NotNull String miniMessage, @NotNull NumberFormat format) {
        Rendered r = render(miniMessage);
        lines.put(index, new LineSpec(r.renderer, format, r.dynamic));
        return this;
    }

    public @NotNull BoardBuilder line(int index, @NotNull ComponentLike component) {
        Component c = component.asComponent();
        lines.put(index, new LineSpec(p -> c, null, false));
        return this;
    }

    public @NotNull BoardBuilder line(int index, @NotNull Animation<Component> animation) {
        lines.put(index, new LineSpec(p -> animation.current(), null, true));
        return this;
    }

    /** Appends a line at the next index (top-to-bottom in call order). */
    public @NotNull BoardBuilder line(@NotNull String miniMessage) {
        return line(nextAutoIndex++, miniMessage);
    }

    /** Appends a line with a number format at the next index. */
    public @NotNull BoardBuilder line(@NotNull String miniMessage, @NotNull NumberFormat format) {
        return line(nextAutoIndex++, miniMessage, format);
    }

    public @NotNull BoardBuilder line(@NotNull ComponentLike component) {
        return line(nextAutoIndex++, component);
    }

    public @NotNull BoardBuilder line(@NotNull Animation<Component> animation) {
        return line(nextAutoIndex++, animation);
    }

    /** Appends an empty spacer line. */
    public @NotNull BoardBuilder blankLine() {
        return line(nextAutoIndex++, Component.empty());
    }

    /** Appends several MiniMessage lines at once, top to bottom. */
    public @NotNull BoardBuilder lines(@NotNull String... miniMessageLines) {
        for (String line : miniMessageLines) {
            line(nextAutoIndex++, line);
        }
        return this;
    }

    /** Appends several MiniMessage lines at once, top to bottom. */
    public @NotNull BoardBuilder lines(@NotNull Iterable<String> miniMessageLines) {
        for (String line : miniMessageLines) {
            line(nextAutoIndex++, line);
        }
        return this;
    }

    // ---- build ------------------------------------------------------------------------------

    /**
     * Creates or reuses the player's sidebar, paints it, and starts auto-refreshing it if any
     * content is dynamic. Returns the live {@link Sidebar}.
     */
    public @NotNull Sidebar build() {
        board.markBuilderOwned(player); // this board is explicitly driven; the global provider yields
        Sidebar sidebar = board.sidebar(player);
        sidebar.lineProcessors(processors); // replace (clears on rebuild / layout switch)
        int maxIndex = lines.isEmpty() ? -1 : lines.lastKey();
        boolean dynamic = titleDynamic || lines.values().stream().anyMatch(LineSpec::dynamic);

        Runnable apply = () -> {
            if (sidebar.closed() || !player.isOnline()) {
                return;
            }
            // Clear first so rebuilding/switching to a shorter layout can't leave stale trailing
            // lines. All mutations here run before a single debounced flush, so there is no flicker.
            sidebar.clearLines();
            sidebar.title(titleRenderer.apply(player));
            for (int i = 0; i <= maxIndex; i++) {
                LineSpec spec = lines.get(i);
                if (spec == null) {
                    sidebar.line(i, Component.empty());
                } else if (spec.format() != null) {
                    sidebar.line(i, spec.renderer().apply(player), spec.format());
                } else {
                    sidebar.line(i, spec.renderer().apply(player));
                }
            }
        };

        // Initial paint on the player's region thread (safe from any caller thread).
        Schedulers.onEntity(board.plugin(), player, apply);

        if (dynamic) {
            int interval = refreshTicks > 0 ? refreshTicks : 3;
            Schedulers.ScheduledHandle handle = Schedulers.entityTimer(board.plugin(), player, h -> {
                if (sidebar.closed() || !player.isOnline()) {
                    h.cancel();
                    return;
                }
                board.recordRefresh();
                apply.run();
            }, interval, interval);
            // Register so switching layouts / rebuilding cancels this refresh loop.
            board.trackRefresh(player, handle);
        } else {
            board.trackRefresh(player, null);
        }
        return sidebar;
    }

    // ---- internals --------------------------------------------------------------------------

    private record Rendered(Function<Player, Component> renderer, boolean dynamic) {
    }

    private Rendered render(String raw) {
        if (parsePlaceholders && raw.indexOf('%') >= 0) {
            // Resolve placeholders per render, but only re-parse MiniMessage when the resolved string
            // actually changes (placeholders like %online% rarely change every tick). Renderers are
            // per-player and only ever run on that player's region thread, so no synchronisation.
            return new Rendered(new CachingRenderer(raw), true);
        }
        Component parsed = Text.mini(raw);
        return new Rendered(p -> parsed, false);
    }

    /** A per-line renderer that memoises the last resolved string → parsed component. */
    private final class CachingRenderer implements Function<Player, Component> {
        private final String raw;
        private String lastResolved;
        private Component lastComponent;

        CachingRenderer(String raw) {
            this.raw = raw;
        }

        @Override
        public Component apply(Player player) {
            String resolved = board.placeholders().resolveForMiniMessage(player, raw);
            if (!resolved.equals(lastResolved)) {
                lastResolved = resolved;
                lastComponent = Text.mini(resolved);
            }
            return lastComponent;
        }
    }
}
