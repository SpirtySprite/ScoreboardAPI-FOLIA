package net.foliaboard.example;

import net.foliaboard.FoliaBoard;
import net.foliaboard.api.Nametag;
import net.foliaboard.api.animation.Animation;
import net.foliaboard.api.animation.Animations;
import net.foliaboard.api.async.AsyncUtil;
import net.foliaboard.api.event.LayoutApplyEvent;
import net.foliaboard.api.event.SidebarCreateEvent;
import net.foliaboard.api.format.NumberFormat;
import net.foliaboard.api.hook.LineProcessor;
import net.foliaboard.api.layout.Layout;
import net.foliaboard.api.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * A full-capability demo of FoliaBoard. Dropping this jar into a 1.20.6+ Paper/Folia server shows,
 * live, essentially every feature at once:
 *
 * <ul>
 *   <li>Layout profiles ("lobby" / "minigame") with instant switching via {@code /fbdemo}.</li>
 *   <li>Animated MiniMessage titles + a scrolling line, refreshed automatically.</li>
 *   <li>Placeholders (built-ins + custom {@code %rank%}) that update live.</li>
 *   <li>Per-viewer nametags: everyone gets a gold [VIP] prefix, you see "◄ you" on your own name,
 *       and same-world players show green while others show gray.</li>
 *   <li>Below-name hearts and tab-list ping numbers, updated every second.</li>
 *   <li>An animated tab-list header/footer.</li>
 *   <li>A per-line number format (fixed component) in the minigame board.</li>
 *   <li>A global line-processor hook and event listeners ({@link SidebarCreateEvent},
 *       {@link LayoutApplyEvent}).</li>
 * </ul>
 *
 * When you use FoliaBoard as a library you don't need any of this -- just call
 * {@link FoliaBoard#create}. This class is only the demo.
 */
public final class ExamplePlugin extends JavaPlugin implements Listener {
    private FoliaBoard board;
    private int tick;

    // Self-timed animations reused by the layouts and header.
    private final Animation<Component> lobbyTitle = Animations.cycle(Duration.ofMillis(500),
            Text.mini("<gradient:#00c6ff:#0072ff><bold>✦ LOBBY ✦</bold></gradient>"),
            Text.mini("<gradient:#0072ff:#00c6ff><bold>✦ LOBBY ✦</bold></gradient>"));
    private final Animation<Component> scroller = Animations.scrollText(Duration.ofMillis(150),
            "welcome to the server — have fun and be kind!", 24, TextColor.color(0x8AB4F8));
    private final Animation<Component> headerPulse = Animations.pulseColor(Duration.ofSeconds(3),
            "◆ FOLIABOARD NETWORK ◆", TextColor.color(0x00C6FF), TextColor.color(0xFFFFFF));

    @Override
    public void onEnable() {
        this.board = FoliaBoard.create(this);

        // --- placeholders -------------------------------------------------------------------
        board.placeholders().register("rank", p -> p.isOp() ? "Admin" : "Member");
        board.placeholders().register("coins", p -> String.valueOf(1000 + Math.floorMod(p.getName().hashCode(), 500)));

        // --- a global hook: turn off the italics some resource packs add, without touching text --
        board.addLineProcessor((viewer, index, line) ->
                index == LineProcessor.TITLE ? line : line.decoration(TextDecoration.ITALIC, false));

        // --- two switchable layout profiles -------------------------------------------------
        board.registerLayout(Layout.named("lobby", b -> b
                .placeholders(true)
                .refreshEvery(4)
                .title(lobbyTitle)
                .blankLine()
                .line("<gray>Player: <white>%player%")
                .line("<gray>Rank: <gold>%rank%")
                .line("<gray>Coins: <yellow>%coins%")
                .line("<gray>Online: <green>%online%")
                .line("<gray>Ping: <aqua>%ping%ms")
                .blankLine()
                .line(scroller)
                .blankLine()
                .line("<yellow>play.foliaboard.net")));

        board.registerLayout(Layout.named("minigame", b -> b
                .placeholders(true)
                .refreshEvery(4)
                .title("<gradient:#ff512f:#dd2476><bold>⚔ SKYWARS ⚔</bold></gradient>")
                .blankLine()
                // number-format demo: the kill count is shown as the score number on the right
                .line("<gray>Kills", NumberFormat.fixed(Text.mini("<red><bold>0</bold>")))
                .line("<gray>Time: <white>05:00")
                .line("<gray>Map: <aqua>Islands")
                .blankLine()
                .line("<gray>Players: <green>%online%")
                .blankLine()
                .line("<yellow>play.foliaboard.net")));

        getServer().getPluginManager().registerEvents(this, this);

        // --- live numbers, nametags and header, once a second -------------------------------
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tickLive(), 20L, 20L);

        getLogger().info("FoliaBoard demo enabled — join and try /fbdemo lobby | minigame.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Sidebar via the lobby layout (switch any time with /fbdemo).
        board.applyLayout(player, "lobby");

        // Per-viewer nametag: gold [VIP] for all; you see "◄ you"; same-world players show green.
        board.createNametag(player)
                .prefix("<gold>[VIP] ")
                .color(NamedTextColor.YELLOW)
                .tabSort(player.isOp() ? 0 : 100) // ops sort to the top of the tab list
                .perViewer((viewer, target, style) -> {
                    if (viewer.equals(target)) {
                        style.suffix(" <yellow>◄ you");
                    } else if (viewer.getWorld().equals(target.getWorld())) {
                        style.color(NamedTextColor.GREEN);
                    } else {
                        style.color(NamedTextColor.GRAY);
                    }
                })
                .apply();

        // Below-name hearts + tab ping (kept fresh by tickLive()).
        board.belowName().title(Text.mini("<red>❤")).score(player, (int) Math.round(player.getHealth()));
        board.tabList().score(player, player.getPing());

        // Tab-list entry styling - DIFFERENT from the above-head prefix, and using no team at all.
        // Above head shows gold "[VIP]"; the tab list shows an aqua star instead.
        board.tabName(player, "<aqua>★ <white>" + player.getName());
        board.tabOrder(player, player.isOp() ? 100 : 0);   // ops sorted to the top (1.21.2+)
    }

    /** Runs on the global region thread once a second; hops to each player for live values. */
    private void tickLive() {
        tick++;
        boolean reapplyNametags = tick % 3 == 0; // every 3s, so per-viewer colours track world moves
        for (Player player : Bukkit.getOnlinePlayers()) {
            AsyncUtil.onPlayer(this, player, () -> {
                board.belowName().score(player, (int) Math.round(player.getHealth()));
                board.tabList().score(player, player.getPing());
                board.tabHeaderFooter(player,
                        Component.empty().append(headerPulse.current()),
                        Text.mini("<gray>Online: <green>" + Bukkit.getOnlinePlayers().size()
                                + " <dark_gray>| <gray>ms: <aqua>" + player.getPing()));
            });
            if (reapplyNametags) {
                Nametag tag = board.nametagIfPresent(player);
                if (tag != null) {
                    tag.apply();
                }
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String which = args.length > 0 ? args[0].toLowerCase() : "lobby";
        if (which.equals("lobby") || which.equals("minigame")) {
            board.applyLayout(player, which);
            player.sendMessage(Text.mini("<green>Switched to the <white>" + which + "<green> board."));
        } else {
            player.sendMessage(Text.mini("<red>Usage: /fbdemo <lobby|minigame>"));
        }
        return true;
    }

    // --- event hooks demonstrating the extensibility API ------------------------------------

    @EventHandler
    public void onSidebarCreate(SidebarCreateEvent event) {
        getLogger().info("Created a sidebar for " + event.getPlayer().getName());
    }

    @EventHandler
    public void onLayoutApply(LayoutApplyEvent event) {
        // Other plugins could cancel or swap the layout here (e.g. per-rank overrides).
        getLogger().fine("Applying layout '" + event.getLayout().name() + "' to " + event.getPlayer().getName());
    }

    @Override
    public void onDisable() {
        if (board != null) {
            board.close();
        }
    }
}
