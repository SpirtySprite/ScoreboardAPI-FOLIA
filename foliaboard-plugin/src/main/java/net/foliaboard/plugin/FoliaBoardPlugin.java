package net.foliaboard.plugin;

import net.foliaboard.FoliaBoard;
import net.foliaboard.api.ScoreboardAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * The standalone FoliaBoard plugin - the installable jar you publish (e.g. on Modrinth).
 * <p>
 * It does nothing visible on its own: on enable it initialises the {@link ScoreboardAPI} so that any
 * other plugin which soft-depends on {@code FoliaBoard} can use the API without bundling it:
 *
 * <pre>{@code
 * // your plugin.yml:  softdepend: [FoliaBoard]
 * FoliaBoard board = FoliaBoardPlugin.api();          // or ScoreboardAPI.get()
 * board.createBoard(player).title("<aqua>Hi").build();
 * }</pre>
 *
 * Prefer this (install once, many plugins share it) <i>or</i> shade {@code foliaboard-core} into your
 * own plugin - not both.
 */
public final class FoliaBoardPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        ScoreboardAPI.init(this);
        getLogger().info("FoliaBoard v" + getPluginMeta().getVersion()
                + " ready — API available to plugins that softdepend on FoliaBoard.");
    }

    @Override
    public void onDisable() {
        ScoreboardAPI.shutdown();
    }

    /** @return the shared FoliaBoard instance for soft-dependents (same as {@link ScoreboardAPI#get()}). */
    public static @NotNull FoliaBoard api() {
        return ScoreboardAPI.get();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        sender.sendMessage("§bFoliaBoard §7v" + getPluginMeta().getVersion()
                + " §8— §7Folia-native scoreboard / nametag / tab API. API: "
                + (ScoreboardAPI.isInitialised() ? "§aready" : "§cnot ready"));
        return true;
    }
}
