package net.foliaboard.api.placeholder;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a single placeholder key (the text between the {@code %} signs) for a player.
 * Return {@code null} to signal "not mine" so the next resolver -- or PlaceholderAPI --
 * gets a chance.
 */
@FunctionalInterface
public interface PlaceholderResolver {
    @Nullable String resolve(@NotNull Player player, @NotNull String key);
}
