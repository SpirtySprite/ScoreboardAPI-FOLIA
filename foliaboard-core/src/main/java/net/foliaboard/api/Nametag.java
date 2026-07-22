package net.foliaboard.api;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Controls the text around a player's name: the prefix and suffix shown above their head and in the
 * tab list, their name colour, nametag visibility and collision. Backed by a scoreboard team sent at
 * the packet level, so it does not fight with Bukkit's team API or other plugins.
 * <p>
 * Like everything in FoliaBoard, setters are safe to call from any thread; changes are dispatched to
 * each viewer on the correct region thread. Call {@link #apply()} after a batch of changes (or just
 * rely on FoliaBoard applying it for you when created).
 */
public interface Nametag {

    /** @return the player whose name this nametag styles. */
    @NotNull Player target();

    @NotNull Nametag prefix(@NotNull ComponentLike prefix);

    @NotNull Nametag suffix(@NotNull ComponentLike suffix);

    /** Sets the name colour (also determines the colour teammates see through walls, etc.). */
    @NotNull Nametag color(@Nullable NamedTextColor color);

    /** ALWAYS, NEVER, HIDE_FOR_OTHER_TEAMS, HIDE_FOR_OWN_TEAM. */
    @NotNull Nametag nametagVisibility(@NotNull Visibility visibility);

    /** ALWAYS, NEVER, PUSH_OTHER_TEAMS, PUSH_OWN_TEAM. */
    @NotNull Nametag collision(@NotNull Collision collision);

    /**
     * Installs a per-viewer resolver so this target's name can look different to different players
     * (ally/enemy colouring, per-viewer ranks, …). Pass {@code null} to go back to a single global
     * style. The global setters above act as the defaults the resolver starts from.
     */
    @NotNull Nametag perViewer(@Nullable NametagResolver resolver);

    /**
     * Pushes the current state to all viewers. Called automatically when the nametag is first
     * created and whenever a new player joins; call it yourself after changing properties.
     */
    @NotNull Nametag apply();

    /** Removes the nametag styling for everyone. */
    void remove();

    enum Visibility {ALWAYS, NEVER, HIDE_FOR_OTHER_TEAMS, HIDE_FOR_OWN_TEAM}

    enum Collision {ALWAYS, NEVER, PUSH_OTHER_TEAMS, PUSH_OWN_TEAM}
}
