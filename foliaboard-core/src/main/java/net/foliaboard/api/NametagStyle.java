package net.foliaboard.api;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A mutable, per-viewer view of a nametag's style, handed to a {@link NametagResolver}. Start from
 * the nametag's global defaults and override whatever should differ for this particular viewer
 * (e.g. show allies a green name, enemies a red one). Strings are MiniMessage.
 */
public interface NametagStyle {

    @NotNull NametagStyle prefix(@NotNull String miniMessage);

    @NotNull NametagStyle prefix(@NotNull ComponentLike prefix);

    @NotNull NametagStyle suffix(@NotNull String miniMessage);

    @NotNull NametagStyle suffix(@NotNull ComponentLike suffix);

    @NotNull NametagStyle color(@Nullable NamedTextColor color);

    @NotNull NametagStyle nametagVisibility(@NotNull Nametag.Visibility visibility);

    @NotNull NametagStyle collision(@NotNull Nametag.Collision collision);
}
