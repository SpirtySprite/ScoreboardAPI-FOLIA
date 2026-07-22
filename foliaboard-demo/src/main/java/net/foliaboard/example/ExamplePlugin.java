package net.foliaboard.example;

import net.foliaboard.FoliaBoard;
import net.foliaboard.api.Nametag;
import net.foliaboard.api.animation.Animation;
import net.foliaboard.api.animation.Animations;
import net.foliaboard.api.async.AsyncUtil;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A full showcase of FoliaBoard, built like a small network hub. Join and use {@code /fbdemo}:
 *
 * <ul>
 *   <li>{@code /fbdemo lobby}    - a live lobby board (rank, coins, ping, position, scrolling tip)</li>
 *   <li>{@code /fbdemo game}     - a fake SkyWars match: countdown, live clock, kills, leaderboard</li>
 *   <li>{@code /fbdemo showcase} - every animation type on one board</li>
 *   <li>{@code /fbdemo duel <p>} - per-viewer demo: you and the target see each other as red enemies
 *                                   in the nametag AND the tab list, plus a duel board with HP bars</li>
 *   <li>{@code /fbdemo stats}    - print board.stats()</li>
 * </ul>
 *
 * The dynamic content is driven by custom placeholders that read per-player state, so the layouts
 * stay declarative and FoliaBoard's auto-refresh does the rest.
 */
public final class ExamplePlugin extends JavaPlugin implements Listener {

    private enum Mode {LOBBY, GAME, SHOWCASE, DUEL}

    private static final String[] BOTS = {"Ph1lza", "Technoblade", "Grian", "Sapnap", "Etho"};

    private static final class State {
        Mode mode = Mode.LOBBY;
        String phase = "COUNTDOWN";
        int countdown = 10;
        int clock = 0;
        int kills = 0;
        int endLeft = 0;
        int hp = 20;
        UUID opponent;
        final int[] botScores = new int[BOTS.length];
    }

    private FoliaBoard board;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    // Animations reused across layouts.
    private final Animation<Component> lobbyTitle = Animations.cycle(Duration.ofMillis(500),
            Text.mini("<gradient:#00c6ff:#7b2ff7><bold>✦ NETWORK ✦</bold></gradient>"),
            Text.mini("<gradient:#7b2ff7:#00c6ff><bold>✦ NETWORK ✦</bold></gradient>"));
    private final Animation<Component> gameTitle = Animations.pulseColor(Duration.ofSeconds(2),
            "⚔ SKYWARS ⚔", TextColor.color(0xff512f), TextColor.color(0xffd200));
    private final Animation<Component> tip = Animations.scrollText(Duration.ofMillis(140),
            "welcome to the hub — try /fbdemo game, /fbdemo duel <player> or /fbdemo showcase   ",
            26, TextColor.color(0x8AB4F8));
    private final Animation<Component> header = Animations.pulseColor(Duration.ofSeconds(3),
            "◆ FOLIABOARD NETWORK ◆", TextColor.color(0x00C6FF), TextColor.color(0xFFFFFF));

    // Showcase animations.
    private final Animation<Component> scCycle = Animations.cycle(Duration.ofMillis(350),
            Text.mini("<red>cycling</red>"), Text.mini("<green>through</green>"),
            Text.mini("<aqua>frames</aqua>"), Text.mini("<yellow>smoothly</yellow>"));
    private final Animation<Component> scScroll = Animations.scrollText(Duration.ofMillis(140),
            "◄ this line scrolls left forever ►   ", 22, TextColor.color(0x00e5ff));
    private final Animation<Component> scPulse = Animations.pulseColor(Duration.ofSeconds(2),
            "pulsing between two colors", TextColor.color(0xff2d55), TextColor.color(0xffcc00));
    private final Animation<Component> scType = Animations.typewriter(Duration.ofMillis(90),
            Component.text("typewriter reveal...", NamedTextColor.GREEN));

    @Override
    public void onEnable() {
        board = FoliaBoard.create(this);

        registerPlaceholders();
        registerLayouts();

        // A global hook: strip stray italics that some fonts/resource packs add to components.
        board.addLineProcessor((viewer, index, line) ->
                index == LineProcessor.TITLE ? line : line.decoration(TextDecoration.ITALIC, false));

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> tick(), 20L, 20L);

        getLogger().info("FoliaBoard demo enabled. Join and run /fbdemo.");
    }

    // ---- placeholders backed by per-player state --------------------------------------------

    private void registerPlaceholders() {
        var ph = board.placeholders();
        ph.register("rank", p -> p.isOp() ? "Admin" : "Member");
        ph.register("coins", p -> String.valueOf(1000 + Math.floorMod(p.getName().hashCode(), 9000)));
        ph.register("pos", p -> {
            var l = p.getLocation();
            return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
        });
        ph.register("status", p -> state(p).phase);
        ph.register("clock", p -> {
            State s = state(p);
            return s.mode == Mode.GAME && s.phase.equals("LIVE") ? mmss(s.clock) : "--:--";
        });
        ph.register("kills", p -> String.valueOf(state(p).kills));
        ph.register("top1", p -> leaderboard(state(p), p, 0));
        ph.register("top2", p -> leaderboard(state(p), p, 1));
        ph.register("top3", p -> leaderboard(state(p), p, 2));
        ph.register("opponent", p -> {
            Player o = opponent(p);
            return o == null ? "nobody" : o.getName();
        });
        ph.register("myhp", p -> bar(state(p).hp));
        ph.register("ophp", p -> {
            Player o = opponent(p);
            return bar(o == null ? 0 : state(o).hp);
        });
    }

    private void registerLayouts() {
        board.registerLayout(Layout.named("lobby", b -> b
                .placeholders(true).refreshEvery(4)
                .title(lobbyTitle)
                .blankLine()
                .line("<gray>Welcome, <white>%player%")
                .line("<gray>Rank: <gold>%rank%")
                .line("<gray>Coins: <yellow>%coins%")
                .blankLine()
                .line("<gray>Online: <green>%online%  <dark_gray>|  <gray>Ping: <aqua>%ping%ms")
                .line("<gray>Position: <white>%pos%")
                .blankLine()
                .line(tip)
                .blankLine()
                .line("<gradient:#f7971e:#ffd200>play.foliaboard.net</gradient>")));

        board.registerLayout(Layout.named("game", b -> b
                .placeholders(true).refreshEvery(4)
                .title(gameTitle)
                .blankLine()
                .line("<gray>Status: <yellow>%status%")
                .line("<gray>Time: <white>%clock%")
                // a number format: the streak shows as the score number on the right
                .line("<gray>Streak", NumberFormat.fixed(Text.mini("<gold>x3")))
                .line("<gray>Your kills: <red><bold>%kills%")
                .blankLine()
                .line("<yellow><bold>Leaderboard")
                .line("<white>1. <gray>%top1%")
                .line("<white>2. <gray>%top2%")
                .line("<white>3. <gray>%top3%")
                .blankLine()
                .line("<gradient:#ff512f:#dd2476>play.foliaboard.net</gradient>")));

        board.registerLayout(Layout.named("showcase", b -> b
                .placeholders(true).refreshEvery(2)
                .title(Animations.cycle(Duration.ofMillis(400),
                        Text.mini("<gradient:#f00:#ff0><bold>ANIMATIONS</bold></gradient>"),
                        Text.mini("<gradient:#0f0:#0ff><bold>ANIMATIONS</bold></gradient>"),
                        Text.mini("<gradient:#00f:#f0f><bold>ANIMATIONS</bold></gradient>")))
                .blankLine()
                .line(scCycle)
                .line(scScroll)
                .line(scPulse)
                .line(scType)
                .line("<gradient:#12c2e9:#c471ed:#f64f59>static gradient</gradient>")
                .blankLine()
                .line("<gray>/fbdemo lobby to go back")));

        board.registerLayout(Layout.named("duel", b -> b
                .placeholders(true).refreshEvery(4)
                .title("<gradient:#eb3349:#f45c43><bold>⚔ DUEL ⚔</bold></gradient>")
                .blankLine()
                .line("<white>%player%")
                .line("<red>%myhp%")
                .blankLine()
                .line("<gray>-------- vs --------")
                .blankLine()
                .line("<white>%opponent%")
                .line("<red>%ophp%")
                .blankLine()
                .line("<gray>/fbdemo reset to end")));
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        states.put(player.getUniqueId(), new State());

        board.applyLayout(player, "lobby");
        applyNametag(player);
        board.belowName().title(Text.mini("<red>❤")).score(player, (int) Math.round(player.getHealth()));
        board.tabList().score(player, player.getPing());
        board.tabName(player, "<aqua>★ <white>" + player.getName());
        board.tabOrder(player, player.isOp() ? 100 : 0);

        player.sendMessage(Text.mini("<gradient:#00c6ff:#7b2ff7><bold>FoliaBoard demo</bold></gradient> "
                + "<gray>- try <yellow>/fbdemo game<gray>, <yellow>/fbdemo showcase<gray> or "
                + "<yellow>/fbdemo duel <player>"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        State s = states.remove(id);
        if (s != null && s.opponent != null) {
            endDuel(Bukkit.getPlayer(s.opponent)); // free the other side
        }
    }

    /** Once a second: advance game/duel state, refresh numbers, header, and nametags. */
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = state(p);
            if (s.mode == Mode.GAME) {
                advanceGame(s);
            }
            AsyncUtil.onPlayer(this, p, () -> {
                s.hp = (int) Math.round(p.getHealth());
                board.belowName().score(p, s.hp);
                board.tabList().score(p, p.getPing());
                board.tabHeaderFooter(p, Component.empty().append(header.current()),
                        Text.mini("<gray>Online: <green>" + Bukkit.getOnlinePlayers().size()
                                + "  <dark_gray>|  <gray>ms: <aqua>" + p.getPing()));
            });
        }
    }

    private void advanceGame(State s) {
        var rnd = ThreadLocalRandom.current();
        if (s.countdown > 0) {                 // pre-match countdown
            s.countdown--;
            if (s.countdown == 0) {            // match starts
                s.phase = "LIVE";
                s.clock = 60;
                s.kills = 0;
                java.util.Arrays.fill(s.botScores, 0);
            } else {
                s.phase = "Starting in " + s.countdown + "s";
            }
        } else if (s.phase.equals("LIVE")) {   // match running
            s.clock--;
            if (rnd.nextBoolean()) {
                s.kills++;
            }
            s.botScores[rnd.nextInt(BOTS.length)] += rnd.nextInt(3);
            if (s.clock <= 0) {
                s.phase = "VICTORY!";
                s.endLeft = 5;
            }
        } else if (--s.endLeft <= 0) {          // post-match, then loop
            s.countdown = 10;
            s.phase = "Starting in 10s";
        }
    }

    // ---- command ----------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        switch (sub) {
            case "lobby" -> setMode(player, Mode.LOBBY, "lobby");
            case "showcase" -> setMode(player, Mode.SHOWCASE, "showcase");
            case "game" -> {
                State s = state(player);
                s.mode = Mode.GAME;
                s.phase = "COUNTDOWN";
                s.countdown = 10;
                board.applyLayout(player, "game");
                player.sendMessage(Text.mini("<green>SkyWars starting - watch the board!"));
            }
            case "duel" -> {
                if (args.length < 2) {
                    player.sendMessage(Text.mini("<red>Usage: /fbdemo duel <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || target.equals(player)) {
                    player.sendMessage(Text.mini("<red>Pick another online player."));
                    return true;
                }
                startDuel(player, target);
            }
            case "reset" -> endDuel(player); // resets duel state (if any) and repaints the lobby
            case "stats" -> player.sendMessage(Text.mini("<aqua>" + board.stats()));
            default -> player.sendMessage(Text.mini("<gradient:#00c6ff:#7b2ff7><bold>/fbdemo</bold></gradient> "
                    + "<gray>- <yellow>lobby<gray>, <yellow>game<gray>, <yellow>showcase<gray>, "
                    + "<yellow>duel <player><gray>, <yellow>stats<gray>, <yellow>reset"));
        }
        return true;
    }

    private void setMode(Player player, Mode mode, String layout) {
        state(player).mode = mode;
        board.applyLayout(player, layout);
    }

    private void startDuel(Player a, Player b) {
        state(a).mode = Mode.DUEL;
        state(a).opponent = b.getUniqueId();
        state(b).mode = Mode.DUEL;
        state(b).opponent = a.getUniqueId();
        board.applyLayout(a, "duel");
        board.applyLayout(b, "duel");
        applyNametag(a);
        applyNametag(b);
        if (board.perViewerTabSupported()) {
            board.tabNameFor(a, b, "<red>⚔ " + b.getName());
            board.tabNameFor(b, a, "<red>⚔ " + a.getName());
        }
        a.sendMessage(Text.mini("<red><bold>DUEL!</bold> <gray>vs <white>" + b.getName()));
        b.sendMessage(Text.mini("<red><bold>DUEL!</bold> <gray>vs <white>" + a.getName()));
    }

    /** Ends any duel and returns both players to the lobby. Also a general "reset to lobby". */
    private void endDuel(Player p) {
        if (p == null) {
            return;
        }
        State s = state(p);
        Player o = s.opponent == null ? null : Bukkit.getPlayer(s.opponent);
        s.opponent = null;
        s.mode = Mode.LOBBY;
        board.applyLayout(p, "lobby");
        applyNametag(p);
        if (o != null) {
            State os = state(o);
            os.opponent = null;
            os.mode = Mode.LOBBY;
            board.applyLayout(o, "lobby");
            applyNametag(o);
            if (board.perViewerTabSupported()) {
                board.resetTabNameFor(p, o);
                board.resetTabNameFor(o, p);
            }
        }
    }

    /** Per-viewer nametag: gold [VIP]; you see "you"; duel opponents see each other in red. */
    private void applyNametag(Player target) {
        board.createNametag(target)
                .prefix("<gold>[VIP] ")
                .color(NamedTextColor.YELLOW)
                .tabSort(target.isOp() ? 0 : 100)
                .perViewer((viewer, tgt, style) -> {
                    if (isEnemy(viewer, tgt)) {
                        style.prefix("<red>⚔ ").color(NamedTextColor.RED);
                    } else if (viewer.equals(tgt)) {
                        style.suffix(" <yellow>◄ you");
                    } else if (viewer.getWorld().equals(tgt.getWorld())) {
                        style.color(NamedTextColor.GREEN);
                    }
                })
                .apply();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private boolean isEnemy(Player viewer, Player target) {
        State v = states.get(viewer.getUniqueId());
        return v != null && v.mode == Mode.DUEL && target.getUniqueId().equals(v.opponent);
    }

    private Player opponent(Player p) {
        State s = states.get(p.getUniqueId());
        return s == null || s.opponent == null ? null : Bukkit.getPlayer(s.opponent);
    }

    private static String mmss(int seconds) {
        int s = Math.max(0, seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static String bar(int hp) {
        int filled = Math.max(0, Math.min(10, Math.round(hp / 2f)));
        return "█".repeat(filled) + "░".repeat(10 - filled) + "  " + hp + "/20";
    }

    /** Formats the i-th leaderboard entry (bots + the player, sorted by score). */
    private static String leaderboard(State s, Player self, int index) {
        String[] names = new String[BOTS.length + 1];
        int[] scores = new int[BOTS.length + 1];
        for (int i = 0; i < BOTS.length; i++) {
            names[i] = BOTS[i];
            scores[i] = s.botScores[i];
        }
        names[BOTS.length] = self.getName();
        scores[BOTS.length] = s.kills;
        boolean[] used = new boolean[names.length];
        for (int rank = 0; rank <= index && rank < names.length; rank++) {
            int best = -1;
            for (int i = 0; i < names.length; i++) {
                if (!used[i] && (best == -1 || scores[i] > scores[best])) {
                    best = i;
                }
            }
            used[best] = true;
            if (rank == index) {
                String tag = names[best].equals(self.getName()) ? " (you)" : "";
                return names[best] + tag + "  " + scores[best];
            }
        }
        return "-";
    }

    @Override
    public void onDisable() {
        if (board != null) {
            board.close();
        }
    }
}
