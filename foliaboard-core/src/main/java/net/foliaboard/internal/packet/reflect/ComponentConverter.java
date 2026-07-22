package net.foliaboard.internal.packet.reflect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;

/**
 * Converts Adventure {@link Component}s into vanilla {@code net.minecraft.network.chat.Component}s.
 * <p>
 * Two strategies, tried in order:
 * <ol>
 *   <li>PaperAdventure.asVanilla - the exact path Paper uses internally. Fast and
 *       lossless. Preferred on any Paper/Folia server.</li>
 *   <li>JSON round-trip - serialize with Adventure's Gson serializer and parse with the
 *       vanilla {@code Component.Serializer}. Slower but a safe fallback if Paper's internal class
 *       ever moves.</li>
 * </ol>
 */
final class ComponentConverter {
    private Method paperAsVanilla;             // strategy 1

    private Object gsonSerializer;             // strategy 2 pieces
    private Method gsonSerialize;
    private Method nmsFromJson;
    private Object registryAccess;
    private boolean fromJsonWantsProvider;

    ComponentConverter() {
        trySetupPaper();
        trySetupJson();
        if (paperAsVanilla == null && (nmsFromJson == null || gsonSerialize == null)) {
            throw new IllegalStateException("FoliaBoard: could not initialise any component converter "
                    + "(neither PaperAdventure nor the JSON path resolved).");
        }
    }

    private void trySetupPaper() {
        try {
            Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
            for (Method m : paperAdventure.getDeclaredMethods()) {
                if (m.getName().equals("asVanilla") && m.getParameterCount() == 1
                        && Component.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.setAccessible(true);
                    paperAsVanilla = m;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Not Paper, or class moved: fall back to JSON.
        }
    }

    private void trySetupJson() {
        try {
            // Adventure Gson serializer (present at runtime on Paper), accessed reflectively so we
            // don't need a hard compile-time dependency on adventure-text-serializer-gson.
            Class<?> gcs = Class.forName("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            Object serializer = gcs.getMethod("gson").invoke(null);
            Method serialize = null;
            for (Method m : gcs.getMethods()) {
                if (m.getName().equals("serialize") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(Component.class)) {
                    serialize = m;
                    break;
                }
            }
            this.gsonSerializer = serializer;
            this.gsonSerialize = serialize;

            Class<?> nmsComponent = Reflect.clazz("net.minecraft.network.chat.Component");
            Class<?> serializerClass = Reflect.clazz("net.minecraft.network.chat.Component$Serializer");
            // fromJson(String, HolderLookup.Provider) on 1.20.5+, or fromJson(String) on older builds.
            Method twoArg = null;
            Method oneArg = null;
            for (Method m : serializerClass.getDeclaredMethods()) {
                if (!m.getName().equals("fromJson")) {
                    continue;
                }
                if (m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class) {
                    twoArg = m;
                } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    oneArg = m;
                }
            }
            if (twoArg != null) {
                twoArg.setAccessible(true);
                nmsFromJson = twoArg;
                fromJsonWantsProvider = true;
                registryAccess = resolveRegistryAccess();
            } else if (oneArg != null) {
                oneArg.setAccessible(true);
                nmsFromJson = oneArg;
                fromJsonWantsProvider = false;
            }
        } catch (Throwable ignored) {
            // JSON path unavailable; PaperAdventure path must carry us.
        }
    }

    private static Object resolveRegistryAccess() {
        try {
            Object craftServer = Bukkit.getServer();
            Method getServer = craftServer.getClass().getMethod("getServer");
            Object mcServer = getServer.invoke(craftServer);
            Method registryAccess = null;
            Class<?> c = mcServer.getClass();
            while (c != null && registryAccess == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("registryAccess") && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        registryAccess = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            return registryAccess == null ? null : registryAccess.invoke(mcServer);
        } catch (Throwable t) {
            return null;
        }
    }

    // The empty component appears constantly (blank spacer lines, empty titles/objective names).
    // Converting it once and reusing it avoids a serialize/parse per blank line per flush.
    private volatile Object cachedEmpty;

    /** @return a {@code net.minecraft.network.chat.Component} for the given Adventure component. */
    Object toVanilla(Component component) {
        if (component == null || component == Component.empty() || Component.empty().equals(component)) {
            Object empty = cachedEmpty;
            if (empty == null) {
                empty = convert(Component.empty());
                cachedEmpty = empty;
            }
            return empty;
        }
        return convert(component);
    }

    private Object convert(Component component) {
        if (paperAsVanilla != null) {
            try {
                return paperAsVanilla.invoke(null, component);
            } catch (Throwable t) {
                // fall through to JSON
            }
        }
        try {
            String json = (String) gsonSerialize.invoke(gsonSerializer, component);
            if (fromJsonWantsProvider) {
                return nmsFromJson.invoke(null, json, registryAccess);
            }
            return nmsFromJson.invoke(null, json);
        } catch (Throwable t) {
            throw new IllegalStateException("FoliaBoard: failed to convert component to vanilla", t);
        }
    }
}
