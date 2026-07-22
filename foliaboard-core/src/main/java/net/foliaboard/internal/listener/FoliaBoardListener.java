package net.foliaboard.internal.listener;

import net.foliaboard.FoliaBoard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Bridges Bukkit join/quit into FoliaBoard's lifecycle. On Folia these events fire on the region
 * thread that owns the player, which is exactly where we want to touch that player's board, so no
 * extra hopping is needed here.
 */
public final class FoliaBoardListener implements Listener {
    private final FoliaBoard foliaBoard;

    public FoliaBoardListener(FoliaBoard foliaBoard) {
        this.foliaBoard = foliaBoard;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        foliaBoard.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        foliaBoard.handleWorldChange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        foliaBoard.handleQuit(event.getPlayer());
    }
}
