package net.foliaboard.api.animation;

/**
 * A value that changes over time. Animations are self-timed: {@link #current()} computes the
 * frame from the wall clock, so you never register or tick them. Just call {@code current()} inside
 * a {@link net.foliaboard.api.SidebarProvider} (or anywhere) and return it.
 *
 * @param <T> the frame type, usually {@link net.kyori.adventure.text.Component}.
 */
@FunctionalInterface
public interface Animation<T> {
    /** @return the frame that should be shown right now. */
    T current();
}
