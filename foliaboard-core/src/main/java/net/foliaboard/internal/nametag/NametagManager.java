package net.foliaboard.internal.nametag;

import net.foliaboard.api.Nametag;
import net.foliaboard.internal.packet.PacketAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Owns every {@link Nametag} and keeps them consistent as players come and go: a new player is sent
 * all existing nametags, and a leaver's own nametag is torn down while every other nametag forgets
 * them as a viewer.
 */
public final class NametagManager {
    private final Plugin plugin;
    private final PacketAdapter adapter;
    private final Map<UUID, NametagImpl> byTarget = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final Supplier<Collection<? extends Player>> online = Bukkit::getOnlinePlayers;

    public NametagManager(Plugin plugin, PacketAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
    }

    /** Gets or creates the nametag for a target, applying it to all current viewers immediately. */
    public @NotNull Nametag get(@NotNull Player target) {
        return get(target, null, true);
    }

    /**
     * Gets or creates the nametag for a target with an optional tab-list sort weight. Vanilla orders
     * the tab list by (internal) team name, so a lower weight sorts the player higher. The weight is
     * baked into the team name, so it is fixed for the life of the nametag.
     * <p>
     * When {@code applyNow} is false the caller is responsible for calling {@link Nametag#apply()}
     * once it has finished configuring the nametag -- this avoids sending a blank team packet
     * immediately followed by a modify packet (the {@link net.foliaboard.api.NametagBuilder} path).
     */
    public @NotNull Nametag get(@NotNull Player target, @Nullable Integer sortWeight, boolean applyNow) {
        NametagImpl existing = byTarget.get(target.getUniqueId());
        if (existing != null && !existing.removed()) {
            return existing;
        }
        NametagImpl impl = new NametagImpl(plugin, adapter, target, generateTeamName(sortWeight), online);
        byTarget.put(target.getUniqueId(), impl);
        if (applyNow) {
            impl.apply();
        }
        return impl;
    }

    /**
     * Builds a unique team name that always fits in vanilla's 16-char limit. The hex of an
     * ever-incrementing counter (max 8 chars) guarantees uniqueness on its own; the optional 4-digit
     * sort prefix (0-9999) brings the worst case to 12 chars, so no truncation is ever needed.
     */
    private String generateTeamName(@Nullable Integer sortWeight) {
        String unique = Integer.toHexString(counter.getAndIncrement());
        String name = sortWeight != null
                ? String.format("%04d", Math.max(0, Math.min(9999, sortWeight))) + unique
                : "fbn" + unique;
        if (name.length() > 16) {
            // Unreachable given the accounting above, but fail loud rather than risk a silent collision.
            throw new IllegalStateException("FoliaBoard: generated team name exceeds 16 chars: " + name);
        }
        return name;
    }

    public @Nullable Nametag getIfPresent(@NotNull Player target) {
        NametagImpl impl = byTarget.get(target.getUniqueId());
        return impl == null || impl.removed() ? null : impl;
    }

    /** A new viewer joined: send them every active nametag. */
    public void onJoin(@NotNull Player viewer) {
        for (NametagImpl impl : byTarget.values()) {
            if (!impl.removed()) {
                impl.applyTo(viewer);
            }
        }
    }

    /** A player left: remove their own nametag, and forget them as a viewer of everyone else's. */
    public void onQuit(@NotNull Player player) {
        UUID id = player.getUniqueId();
        NametagImpl own = byTarget.remove(id);
        if (own != null) {
            own.remove();
        }
        for (NametagImpl impl : byTarget.values()) {
            impl.forgetViewer(id);
        }
    }

    /** @return number of active (not removed) nametags. */
    public int active() {
        int n = 0;
        for (NametagImpl impl : byTarget.values()) {
            if (!impl.removed()) {
                n++;
            }
        }
        return n;
    }

    public void closeAll() {
        for (NametagImpl impl : byTarget.values()) {
            impl.remove();
        }
        byTarget.clear();
    }
}
