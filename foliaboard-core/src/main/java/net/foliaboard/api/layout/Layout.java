package net.foliaboard.api.layout;

import net.foliaboard.FoliaBoard;
import net.foliaboard.api.BoardBuilder;
import net.foliaboard.api.Sidebar;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A reusable, named sidebar template -- a "profile" you can apply to any player and switch
 * between instantly (Lobby vs. Minigame, per-world boards, etc.). A layout is just a recorded recipe
 * of {@link BoardBuilder} calls, so anything the builder can do, a layout can do (MiniMessage,
 * placeholders, animations, number formats).
 *
 * <pre>{@code
 * Layout lobby = Layout.named("lobby", b -> b
 *     .placeholders(true)
 *     .title("<gradient:#00f:#0ff><bold>LOBBY</bold></gradient>")
 *     .blankLine()
 *     .line("<gray>Rank: <white>%rank%")
 *     .line("<gray>Coins: <gold>%coins%"));
 *
 * foliaBoard.registerLayout(lobby);
 * foliaBoard.applyLayout(player, "lobby");    // or foliaBoard.setWorldLayout("world", "lobby");
 * }</pre>
 */
public final class Layout {
    private final String name;
    private final Consumer<BoardBuilder> recipe;

    private Layout(String name, Consumer<BoardBuilder> recipe) {
        this.name = name;
        this.recipe = recipe;
    }

    public static @NotNull Layout named(@NotNull String name, @NotNull Consumer<BoardBuilder> recipe) {
        return new Layout(name, recipe);
    }

    public @NotNull String name() {
        return name;
    }

    /** Builds a fresh board for {@code player} from this template and returns the live sidebar. */
    public @NotNull Sidebar applyTo(@NotNull FoliaBoard board, @NotNull Player player) {
        BoardBuilder builder = board.createBoard(player);
        recipe.accept(builder);
        return builder.build();
    }
}
