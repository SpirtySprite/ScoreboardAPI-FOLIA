package net.foliaboard.internal.tab;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Sets a player's tab-list sort order via Paper's {@code Player#playerListOrder(int)} (added in
 * 1.21.2). Resolved reflectively so the library still compiles/loads on builds that lack it; on those
 * the call is a no-op (sorting then falls back to team-name ordering via nametag {@code tabSort}).
 * <p>
 * This is a Bukkit-level call (no {@code net.minecraft.*} internals), so it is far safer than
 * hand-built player-info packets.
 */
public final class TabOrder {
    private static final Method METHOD = resolve();
    private static volatile boolean warned = false;

    private TabOrder() {
    }

    private static Method resolve() {
        for (String name : new String[]{"playerListOrder", "setPlayerListOrder"}) {
            try {
                Method m = Player.class.getMethod(name, int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /** @return whether tab-list ordering is supported on this server build. */
    public static boolean supported() {
        return METHOD != null;
    }

    public static void set(Player player, int order) {
        if (METHOD == null) {
            if (!warned) {
                warned = true;
                Logger.getLogger("FoliaBoard").info(
                        "Tab-list ordering (Player#playerListOrder) isn't available on this server "
                                + "(needs 1.21.2+); use nametag tabSort for ordering instead.");
            }
            return;
        }
        try {
            METHOD.invoke(player, order);
        } catch (Throwable ignored) {
            // player offline / transient - safe to ignore
        }
    }
}
