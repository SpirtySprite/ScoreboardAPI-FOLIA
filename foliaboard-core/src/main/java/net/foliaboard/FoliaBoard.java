package net.foliaboard;

import net.foliaboard.api.BoardBuilder;
import net.foliaboard.api.Nametag;
import net.foliaboard.api.NametagBuilder;
import net.foliaboard.api.ScoreObjective;
import net.foliaboard.api.Sidebar;
import net.foliaboard.api.SidebarProvider;
import net.foliaboard.api.event.LayoutApplyEvent;
import net.foliaboard.api.event.SidebarCreateEvent;
import net.foliaboard.api.hook.LineProcessor;
import net.foliaboard.api.layout.Layout;
import net.foliaboard.api.placeholder.Placeholders;
import net.foliaboard.internal.board.SidebarImpl;
import net.foliaboard.internal.listener.FoliaBoardListener;
import net.foliaboard.internal.nametag.NametagManager;
import net.foliaboard.internal.objective.ScoreObjectiveImpl;
import net.foliaboard.internal.packet.DisplaySlotType;
import net.foliaboard.internal.packet.PacketAdapter;
import net.foliaboard.internal.packet.PacketAdapterFactory;
import net.foliaboard.internal.scheduler.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main entry point. Create one instance in {@code onEnable} and call {@link #close()} in
 * {@code onDisable}. {@link net.foliaboard.api.ScoreboardAPI} offers a static handle if you'd
 * rather not pass the instance around.
 *
 * <pre>{@code
 * FoliaBoard board = FoliaBoard.create(this);
 * board.createBoard(player)
 *      .title("<aqua>My Server")
 *      .line("<gray>Hi <white>%player%")
 *      .build();
 * }</pre>
 *
 * All methods are thread-safe. Per-player work runs on that player's region thread (Folia) or the
 * main thread (Paper); callers never schedule anything.
 */
public final class FoliaBoard {
    private final Plugin plugin;
    private final PacketAdapter adapter;
    private final Placeholders placeholders = new Placeholders();

    private final Map<UUID, SidebarImpl> sidebars = new ConcurrentHashMap<>();
    private final Map<UUID, Schedulers.ScheduledHandle> refreshHandles = new ConcurrentHashMap<>();
    private final NametagManager nametags;
    private final AtomicInteger objectiveCounter = new AtomicInteger();

    private final List<LineProcessor> lineProcessors = new CopyOnWriteArrayList<>();
    private final Map<String, Layout> layouts = new ConcurrentHashMap<>();
    private final Map<String, String> worldLayouts = new ConcurrentHashMap<>(); // world -> layout name

    // Players whose sidebar is owned by an explicit builder/layout. The global provider yields for
    // these so the two drivers don't fight over the same board.
    private final Set<UUID> builderOwned = ConcurrentHashMap.newKeySet();
    // Players whose current board came from a world layout; used to clear it when they leave.
    private final Set<UUID> worldLayoutOwned = ConcurrentHashMap.newKeySet();

    private volatile ScoreObjectiveImpl belowName;
    private volatile ScoreObjectiveImpl tabList;

    private volatile SidebarProvider globalProvider;
    private volatile Schedulers.ScheduledHandle providerTask;
    private volatile Layout globalLayout;

    private final FoliaBoardListener listener;
    private volatile boolean closed = false;

    private FoliaBoard(Plugin plugin, PacketAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.nametags = new NametagManager(plugin, adapter);
        this.listener = new FoliaBoardListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    /**
     * Initialises FoliaBoard for your plugin. Throws if the server is older than 1.20.6 or the
     * packet layer cannot bind to the server internals.
     */
    public static @NotNull FoliaBoard create(@NotNull Plugin plugin) {
        PacketAdapter adapter = PacketAdapterFactory.create(plugin.getLogger());
        plugin.getLogger().info("FoliaBoard ready (" + (Schedulers.isFolia() ? "Folia" : "Paper") + " scheduling).");
        return new FoliaBoard(plugin, adapter);
    }

    // ---- fluent builders --------------------------------------------------------------------

    /** Starts a fluent sidebar builder for a player. */
    public @NotNull BoardBuilder createBoard(@NotNull Player player) {
        ensureOpen();
        return new BoardBuilder(this, player);
    }

    /** Starts a fluent nametag builder for a player. */
    public @NotNull NametagBuilder createNametag(@NotNull Player player) {
        ensureOpen();
        return new NametagBuilder(this, player);
    }

    // ---- sidebars ---------------------------------------------------------------------------

    /** Gets (or lazily creates) the sidebar for a player. */
    public @NotNull Sidebar sidebar(@NotNull Player player) {
        ensureOpen();
        boolean[] created = {false};
        SidebarImpl sidebar = sidebars.computeIfAbsent(player.getUniqueId(), id -> {
            created[0] = true;
            return new SidebarImpl(plugin, adapter, player, nextObjectiveId(), lineProcessors);
        });
        if (created[0]) {
            // Fire on the player's region thread so the (synchronous) event is valid on Folia,
            // and so it never blocks the caller of sidebar().
            Schedulers.onEntity(plugin, player,
                    () -> Bukkit.getPluginManager().callEvent(new SidebarCreateEvent(player, sidebar)));
        }
        return sidebar;
    }

    public @Nullable Sidebar sidebarIfPresent(@NotNull Player player) {
        return sidebars.get(player.getUniqueId());
    }

    /** Removes and closes a player's sidebar (and stops any auto-refresh driving it). */
    public void removeSidebar(@NotNull Player player) {
        UUID id = player.getUniqueId();
        cancelRefresh(id);
        builderOwned.remove(id);
        worldLayoutOwned.remove(id);
        SidebarImpl removed = sidebars.remove(id);
        if (removed != null) {
            removed.close();
        }
    }

    /** Marks a player's board as owned by an explicit builder/layout, so the provider yields. */
    public void markBuilderOwned(@NotNull Player player) {
        builderOwned.add(player.getUniqueId());
    }

    /**
     * Shows {@code layout} to every player, now and on join. Convenient when the board is the same
     * shape for everyone (with placeholders/animations). Replaces any previous global provider/layout.
     */
    public void setGlobalSidebar(@NotNull Layout layout) {
        ensureOpen();
        registerLayout(layout);
        clearGlobalSidebar();          // a layout and a provider shouldn't both drive boards
        this.globalLayout = layout;
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyLayout(player, layout);
        }
    }

    /** Registers a provider that drives every player's sidebar, refreshed on its interval. */
    public void setGlobalSidebar(@NotNull SidebarProvider provider) {
        ensureOpen();
        this.globalLayout = null;      // switching to a provider disables any global layout
        this.globalProvider = provider;
        Schedulers.ScheduledHandle old = this.providerTask;
        if (old != null) {
            old.cancel();
        }
        int interval = Math.max(1, provider.refreshIntervalTicks());
        this.providerTask = Schedulers.globalTimer(plugin, () -> {
            SidebarProvider current = this.globalProvider;
            if (current == null) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                Schedulers.onEntity(plugin, player, () -> refreshFromProvider(player, current));
            }
        }, interval, interval);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Schedulers.onEntity(plugin, player, () -> refreshFromProvider(player, provider));
        }
    }

    /** Stops the global provider (existing sidebars remain until you remove them). */
    public void clearGlobalSidebar() {
        this.globalProvider = null;
        Schedulers.ScheduledHandle task = this.providerTask;
        if (task != null) {
            task.cancel();
            this.providerTask = null;
        }
    }

    /** Runs on the player's region thread. */
    private void refreshFromProvider(Player player, SidebarProvider provider) {
        if (closed || !player.isOnline()) {
            return;
        }
        // An explicit builder/layout owns this player's board - don't fight it.
        if (builderOwned.contains(player.getUniqueId())) {
            return;
        }
        Sidebar sidebar = sidebar(player);
        if (!provider.visible(player)) {
            sidebar.visible(false);
            return;
        }
        sidebar.visible(true);
        sidebar.title(provider.title(player));
        sidebar.lines(provider.lines(player));
    }

    // ---- layouts / profiles -----------------------------------------------------------------

    /** Registers a named layout so it can be applied by name or bound to a world. */
    public @NotNull FoliaBoard registerLayout(@NotNull Layout layout) {
        layouts.put(layout.name().toLowerCase(), layout);
        return this;
    }

    public @Nullable Layout layout(@NotNull String name) {
        return layouts.get(name.toLowerCase());
    }

    /** Removes a registered layout. Any board currently showing it keeps its content until changed. */
    public @NotNull FoliaBoard unregisterLayout(@NotNull String name) {
        layouts.remove(name.toLowerCase());
        return this;
    }

    /** Applies a registered layout to a player instantly. Throws if no layout has that name. */
    public @NotNull Sidebar applyLayout(@NotNull Player player, @NotNull String layoutName) {
        Layout layout = layout(layoutName);
        if (layout == null) {
            throw new IllegalArgumentException("No layout registered named '" + layoutName + "'");
        }
        return applyLayout(player, layout);
    }

    /**
     * Applies a layout to a player. Fires {@link LayoutApplyEvent} (cancellable, layout swappable)
     * on the player's region thread, then paints the resulting layout. Returns the player's live
     * sidebar handle immediately; if the event is cancelled the board simply keeps its prior content.
     */
    public @NotNull Sidebar applyLayout(@NotNull Player player, @NotNull Layout layout) {
        ensureOpen();
        markBuilderOwned(player); // synchronously, so an already-scheduled provider tick yields
        Sidebar sidebar = sidebar(player);
        Schedulers.onEntity(plugin, player, () -> {
            LayoutApplyEvent event = new LayoutApplyEvent(player, layout);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                event.getLayout().applyTo(this, player);
            }
        });
        return sidebar;
    }

    /** Binds a layout (by name) to a world; players in that world get it on join and world-change. */
    public @NotNull FoliaBoard setWorldLayout(@NotNull String worldName, @NotNull String layoutName) {
        worldLayouts.put(worldName, layoutName);
        return this;
    }

    public @NotNull FoliaBoard clearWorldLayout(@NotNull String worldName) {
        worldLayouts.remove(worldName);
        return this;
    }

    private void applyWorldLayoutIfAny(Player player) {
        UUID id = player.getUniqueId();
        String layoutName = worldLayouts.get(player.getWorld().getName());
        if (layoutName != null) {
            Layout layout = layout(layoutName);
            if (layout != null) {
                applyLayout(player, layout);
                worldLayoutOwned.add(id);
            }
        } else if (worldLayoutOwned.remove(id)) {
            // Left a world-layout world for one with no layout - clear the board so it doesn't linger.
            // If a global provider is set, it repaints on its next tick (ownership is released here).
            removeSidebar(player);
        }
    }

    // ---- nametags ---------------------------------------------------------------------------

    /** Gets or creates the nametag styling for a player. */
    public @NotNull Nametag nametag(@NotNull Player target) {
        ensureOpen();
        return nametags.get(target);
    }

    /**
     * Used by {@link NametagBuilder}; supports an optional tab-list sort weight. Does not apply
     * immediately -- the builder applies once after configuring, avoiding a blank+modify pair.
     */
    public @NotNull Nametag nametagInternal(@NotNull Player target, @Nullable Integer sortWeight) {
        ensureOpen();
        return nametags.get(target, sortWeight, false);
    }

    public @Nullable Nametag nametagIfPresent(@NotNull Player target) {
        return nametags.getIfPresent(target);
    }

    // ---- below-name / tab-list numbers ------------------------------------------------------

    /** The shared objective shown below players' names. Created on first use. */
    public @NotNull ScoreObjective belowName() {
        ensureOpen();
        ScoreObjectiveImpl local = belowName;
        if (local == null) {
            synchronized (this) {
                if (belowName == null) {
                    belowName = new ScoreObjectiveImpl(plugin, adapter, "fb_bn", DisplaySlotType.BELOW_NAME);
                }
                local = belowName;
            }
        }
        return local;
    }

    /** The shared objective shown as numbers in the tab list. Created on first use. */
    public @NotNull ScoreObjective tabList() {
        ensureOpen();
        ScoreObjectiveImpl local = tabList;
        if (local == null) {
            synchronized (this) {
                if (tabList == null) {
                    tabList = new ScoreObjectiveImpl(plugin, adapter, "fb_tab", DisplaySlotType.PLAYER_LIST);
                }
                local = tabList;
            }
        }
        return local;
    }

    // ---- tab-list entry styling & sort (independent of above-head nametags) -----------------

    /**
     * Sets how {@code target} appears in the tab list, for everyone - independent of the
     * above-head nametag. This is how you show a different prefix in tab vs. above the head, and
     * it needs no scoreboard team, so it doesn't conflict with other team-based plugins.
     */
    public void tabName(@NotNull Player target, @NotNull String miniMessage) {
        tabName(target, net.foliaboard.api.text.Text.mini(miniMessage));
    }

    /** Sets {@code target}'s tab-list display name for everyone. */
    public void tabName(@NotNull Player target, @NotNull net.kyori.adventure.text.ComponentLike name) {
        net.kyori.adventure.text.Component c = name.asComponent();
        Schedulers.onEntity(plugin, target, () -> target.playerListName(c));
    }

    /** Reverts {@code target}'s tab-list name to the vanilla default (their real name / team style). */
    public void resetTabName(@NotNull Player target) {
        Schedulers.onEntity(plugin, target, () -> target.playerListName(null));
    }

    /**
     * Sets {@code target}'s tab-list sort order for everyone (Paper 1.21.2+). Higher sorts higher;
     * changing it is instant and flicker-free (no team-name trick). No-op on older builds -- use
     * nametag {@code tabSort} there.
     */
    public void tabOrder(@NotNull Player target, int order) {
        Schedulers.onEntity(plugin, target, () -> net.foliaboard.internal.tab.TabOrder.set(target, order));
    }

    /** @return whether flicker-free tab ordering ({@link #tabOrder}) is supported on this server. */
    public boolean tabOrderSupported() {
        return net.foliaboard.internal.tab.TabOrder.supported();
    }

    // ---- tab-list header / footer -----------------------------------------------------------

    /** Sets this player's tab-list header and footer (MiniMessage strings). */
    public void tabHeaderFooter(@NotNull Player player, @NotNull String header, @NotNull String footer) {
        tabHeaderFooter(player, net.foliaboard.api.text.Text.mini(header), net.foliaboard.api.text.Text.mini(footer));
    }

    /** Sets this player's tab-list header and footer. */
    public void tabHeaderFooter(@NotNull Player player,
                                @NotNull net.kyori.adventure.text.ComponentLike header,
                                @NotNull net.kyori.adventure.text.ComponentLike footer) {
        var h = header.asComponent();
        var f = footer.asComponent();
        Schedulers.onEntity(plugin, player, () -> player.sendPlayerListHeaderAndFooter(h, f));
    }

    /** Clears this player's tab-list header and footer. */
    public void clearTabHeaderFooter(@NotNull Player player) {
        Schedulers.onEntity(plugin, player,
                () -> player.sendPlayerListHeaderAndFooter(net.kyori.adventure.text.Component.empty(),
                        net.kyori.adventure.text.Component.empty()));
    }

    // ---- hooks / misc -----------------------------------------------------------------------

    /** Registers a hook that can rewrite every line/title of every board before it is sent. */
    public @NotNull FoliaBoard addLineProcessor(@NotNull LineProcessor processor) {
        lineProcessors.add(processor);
        return this;
    }

    public @NotNull FoliaBoard removeLineProcessor(@NotNull LineProcessor processor) {
        lineProcessors.remove(processor);
        return this;
    }

    /** The placeholder engine (built-ins + your resolvers + PlaceholderAPI bridge). */
    public @NotNull Placeholders placeholders() {
        return placeholders;
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Records the auto-refresh task driving a player's board, cancelling any previous one. */
    public void trackRefresh(@NotNull Player player, @Nullable Schedulers.ScheduledHandle handle) {
        Schedulers.ScheduledHandle previous = handle == null
                ? refreshHandles.remove(player.getUniqueId())
                : refreshHandles.put(player.getUniqueId(), handle);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelRefresh(UUID id) {
        Schedulers.ScheduledHandle handle = refreshHandles.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    // ---- lifecycle wiring (called by the listener) ------------------------------------------

    public void handleJoin(@NotNull Player player) {
        if (closed) {
            return;
        }
        nametags.onJoin(player);
        if (belowName != null) {
            belowName.onJoin(player);
        }
        if (tabList != null) {
            tabList.onJoin(player);
        }
        SidebarProvider provider = globalProvider;
        if (provider != null) {
            Schedulers.onEntity(plugin, player, () -> refreshFromProvider(player, provider));
        }
        Layout global = globalLayout;
        if (global != null) {
            applyLayout(player, global);
        }
        applyWorldLayoutIfAny(player); // world layout, if any, wins over the global one
    }

    public void handleWorldChange(@NotNull Player player) {
        if (closed) {
            return;
        }
        applyWorldLayoutIfAny(player);
    }

    public void handleQuit(@NotNull Player player) {
        removeSidebar(player);
        nametags.onQuit(player);
        if (belowName != null) {
            belowName.onQuit(player);
        }
        if (tabList != null) {
            tabList.onQuit(player);
        }
    }

    /** Tears everything down. Call from {@code onDisable}. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearGlobalSidebar();
        HandlerList.unregisterAll(listener);
        for (Schedulers.ScheduledHandle handle : refreshHandles.values()) {
            handle.cancel();
        }
        refreshHandles.clear();
        for (SidebarImpl sidebar : sidebars.values()) {
            sidebar.close();
        }
        sidebars.clear();
        nametags.closeAll();
        if (belowName != null) {
            belowName.closeAll();
        }
        if (tabList != null) {
            tabList.closeAll();
        }
    }

    private String nextObjectiveId() {
        return "fb" + Integer.toHexString(objectiveCounter.getAndIncrement());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("FoliaBoard has been closed");
        }
    }
}
