package net.foliaboard.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Computes a nametag's appearance per viewer. Set one with {@link Nametag#perViewer} to make a
 * target's name look different to different players -- the classic ally/enemy colouring, or
 * per-viewer rank display.
 *
 * <pre>{@code
 * board.nametag(target).perViewer((viewer, tgt, style) -> {
 *     if (areAllies(viewer, tgt)) style.color(NamedTextColor.GREEN).prefix("<green>✦ ");
 *     else                        style.color(NamedTextColor.RED).prefix("<red>☠ ");
 * });
 * }</pre>
 *
 * Called on the viewer's region thread each time the nametag is applied to them. The {@code style}
 * starts from the nametag's global defaults; override only what should differ.
 */
@FunctionalInterface
public interface NametagResolver {
    void resolve(@NotNull Player viewer, @NotNull Player target, @NotNull NametagStyle style);
}
