package net.foliaboard.api.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * MiniMessage helpers. Every FoliaBoard method that takes a {@code String} parses it with
 * {@link #mini(String)}, so MiniMessage tags work anywhere a string is accepted, e.g.
 * {@code "<gradient:#f00:#00f>My Server</gradient>"}.
 */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** @return the shared MiniMessage instance (strict, all standard tags). */
    public static @NotNull MiniMessage miniMessage() {
        return MM;
    }

    /** Parses a MiniMessage string into a component. */
    public static @NotNull Component mini(@NotNull String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Parses a MiniMessage string with extra tag resolvers (custom tags, placeholders, etc.). */
    public static @NotNull Component mini(@NotNull String miniMessage, @NotNull TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    /** Serialises a component back to a MiniMessage string (round-trips styling). */
    public static @NotNull String toMini(@NotNull Component component) {
        return MM.serialize(component);
    }

    public static @NotNull Component empty() {
        return Component.empty();
    }
}
