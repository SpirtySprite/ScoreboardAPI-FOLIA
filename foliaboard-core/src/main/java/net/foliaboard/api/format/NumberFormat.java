package net.foliaboard.api.format;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.NotNull;

/**
 * Controls the red number the vanilla client normally draws on the right of each score
 * (a 1.20.3+ feature). FoliaBoard hides these on sidebars by default; use these to override
 * per line or per objective.
 *
 * <ul>
 *   <li>{@link #blank()} - show nothing (clean sidebar look).</li>
 *   <li>{@link #fixed(ComponentLike)} - replace the number with any component (icons, text…).</li>
 *   <li>{@link #styled(Style)} - keep the number but restyle it (colour/bold…).</li>
 *   <li>{@link #defaultFormat()} - the vanilla red number.</li>
 * </ul>
 */
public sealed interface NumberFormat {

    /** Hide the score number entirely. */
    static @NotNull NumberFormat blank() {
        return Blank.INSTANCE;
    }

    /** Replace the score number with a fixed component. */
    static @NotNull NumberFormat fixed(@NotNull ComponentLike component) {
        return new Fixed(component.asComponent());
    }

    /** Keep the numeric score but apply a style (e.g. gold + bold). */
    static @NotNull NumberFormat styled(@NotNull Style style) {
        return new Styled(style);
    }

    /** The vanilla default (red number). */
    static @NotNull NumberFormat defaultFormat() {
        return Default.INSTANCE;
    }

    final class Blank implements NumberFormat {
        public static final Blank INSTANCE = new Blank();
        private Blank() {
        }
    }

    final class Default implements NumberFormat {
        public static final Default INSTANCE = new Default();
        private Default() {
        }
    }

    record Fixed(@NotNull Component component) implements NumberFormat {
    }

    record Styled(@NotNull Style style) implements NumberFormat {
    }
}
