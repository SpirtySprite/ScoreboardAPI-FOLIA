package net.foliaboard.internal.packet;

import net.foliaboard.api.format.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The whole surface FoliaBoard needs from the server's networking layer. Everything above this
 * interface is version-independent, pure-Java logic; everything below it is version-specific packet
 * construction. Swapping server implementations or Minecraft versions means providing a new
 * {@code PacketAdapter} and nothing else.
 * <p>
 * All methods send packets to a single {@code viewer}. FoliaBoard is packet-level and per-player:
 * two players can be shown completely different content for the same objective/team id. Callers are
 * responsible for invoking these on the viewer's own region thread (FoliaBoard does this via the
 * entity scheduler).
 */
public interface PacketAdapter {

    /** @return a short human-readable description of this adapter (for logging). */
    String describe();

    /** Attaches a metrics counter; the adapter increments it per packet. No-op by default. */
    default void attachMetrics(net.foliaboard.internal.metrics.PacketMetrics metrics) {
    }

    /** @return whether {@link #tabDisplayName} works on this server build. */
    default boolean supportsPerViewerTab() {
        return false;
    }

    /**
     * Sets a per-viewer tab-list display name for a target via player-info packets. Pass a null
     * display name to reset. Runs on the viewer's region thread.
     *
     * @return false if unsupported or if the send failed (caller may fall back).
     */
    default boolean tabDisplayName(Player viewer, Player target, @Nullable Component displayName) {
        return false;
    }

    // ---- Objectives -------------------------------------------------------------------------

    void createObjective(Player viewer, String objectiveId, Component title);

    void updateObjective(Player viewer, String objectiveId, Component title);

    void removeObjective(Player viewer, String objectiveId);

    void setDisplaySlot(Player viewer, String objectiveId, DisplaySlotType slot);

    // ---- Scores (sidebar lines / below-name numbers) ----------------------------------------

    /**
     * Sets a score entry. On 1.20.3+ {@code displayName} (if non-null) replaces the visible text of
     * the entry, which is how modern sidebars render arbitrary component lines without teams.
     * {@code numberFormat} (if non-null) overrides the score number for this entry; {@code null}
     * inherits the objective's default.
     */
    void setScore(Player viewer, String objectiveId, String entry, int value,
                  @Nullable Component displayName, @Nullable NumberFormat numberFormat);

    void resetScore(Player viewer, String objectiveId, String entry);

    // ---- Teams (nametags, tab styling, legacy line rendering) -------------------------------

    void createTeam(Player viewer, TeamData team, Collection<String> entries);

    void updateTeam(Player viewer, TeamData team);

    void removeTeam(Player viewer, String teamName);

    void teamEntries(Player viewer, String teamName, Collection<String> entries, boolean add);
}
