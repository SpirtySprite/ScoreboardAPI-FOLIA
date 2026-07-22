package net.foliaboard.internal.packet.reflect;

import net.foliaboard.api.format.NumberFormat;
import net.foliaboard.internal.packet.DisplaySlotType;
import net.foliaboard.internal.packet.PacketAdapter;
import net.foliaboard.internal.packet.TeamData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;

/**
 * Reflection-based {@link PacketAdapter} for modern Paper/Folia (Mojang-mapped runtime, 1.20.6+).
 * <p>
 * This is the only version-specific file in FoliaBoard. Every method builds a vanilla
 * scoreboard packet and pushes it straight down the target player's connection. All reflection
 * handles are resolved once, up front, in the constructor so that an unsupported server version
 * fails loudly at load time instead of mid-game.
 * <p>
 * Sidebar lines use the 1.20.3+ per-score display component (each entry carries its own
 * {@code Component}), so arbitrary component lines render without needing a team per line.
 */
public final class NmsPacketAdapter implements PacketAdapter {
    private final ComponentConverter components = new ComponentConverter();

    // Sending - reflection resolves the members; MethodHandles are the fast path actually used at
    // runtime (with the reflection kept as a fallback if unreflecting isn't permitted).
    private final Method craftPlayerGetHandle;
    private final Field connectionField;
    private final Method sendMethod;
    private final MethodHandle getHandleMH;
    private final MethodHandle connectionGetterMH;
    private final MethodHandle sendMH;

    // Objectives
    private final Object dummyScoreboard;
    private final Object dummyCriteria;
    private final Object renderTypeInteger;
    private final Constructor<?> objectiveCtor;
    private final int objectiveCtorArgs;
    private final Object blankNumberFormat; // hides score numbers on the sidebar; null on old builds
    private final Constructor<?> fixedFormatCtor; // FixedFormat(Component); null if unavailable
    private final Constructor<?> styledFormatCtor; // StyledFormat(Style); null if unavailable
    private final Method adventureStyleToVanilla; // best-effort Adventure Style -> NMS Style
    private final Constructor<?> setObjectivePacketCtor;
    private final Method objectiveGetName; // not strictly needed but resolved for safety

    // Display slot
    private final Class<?> displaySlotClass;
    private final Constructor<?> setDisplayPacketCtor;

    // Scores
    private final Constructor<?> setScorePacketCtor;
    private final Constructor<?> resetScorePacketCtor;
    private final boolean scoreDisplayOptional;   // display arg is Optional<Component> (1.20.5+) vs @Nullable
    private final boolean scoreFormatOptional;    // numberFormat arg is Optional<NumberFormat> vs @Nullable
    private final boolean resetObjectiveOptional; // reset objectiveName arg is Optional<String> vs @Nullable

    // Teams
    private final Class<?> playerTeamClass;
    private final Constructor<?> playerTeamCtor;
    private final Method teamSetPrefix;
    private final Method teamSetSuffix;
    private final Method teamSetDisplayName;
    private final Method teamSetColor;
    private final Method teamSetNametagVisibility;
    private final Method teamSetCollision;
    private final Method teamSetFriendlyFire;
    private final Method teamSetSeeInvisibles;
    private final Method teamGetPlayers;
    private final Class<?> chatFormattingClass;
    private final Class<?> teamVisibilityClass;
    private final Class<?> teamCollisionClass;
    private final Method createAddOrModify;
    private final Method createRemove;
    private final Method createPlayerPacket;
    private final Class<?> teamActionClass;

    public NmsPacketAdapter() {
        // --- packet sending ---
        Class<?> craftPlayer = Reflect.firstClass(
                "org.bukkit.craftbukkit.entity.CraftPlayer");
        craftPlayerGetHandle = Reflect.method(craftPlayer, "getHandle");
        Class<?> serverPlayer = Reflect.clazz("net.minecraft.server.level.ServerPlayer");
        Class<?> gameListener = Reflect.clazz("net.minecraft.server.network.ServerGamePacketListenerImpl");
        connectionField = Reflect.fieldByTypeDeep(serverPlayer, gameListener);
        Class<?> packetClass = Reflect.clazz("net.minecraft.network.protocol.Packet");
        // send(Packet) - declared on ServerCommonPacketListenerImpl (a superclass) since 1.20.2.
        sendMethod = findSend(gameListener, packetClass);

        // Promote the send path to MethodHandles (faster than Method.invoke under load). If the JVM
        // refuses to unreflect for any reason, we silently keep using the reflection members.
        MethodHandle gh = null, cg = null, snd = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            gh = lookup.unreflect(craftPlayerGetHandle);
            cg = lookup.unreflectGetter(connectionField);
            snd = lookup.unreflect(sendMethod);
        } catch (Throwable ignored) {
            gh = null;
            cg = null;
            snd = null;
        }
        getHandleMH = gh;
        connectionGetterMH = cg;
        sendMH = snd;

        // --- objectives ---
        Class<?> scoreboardClass = Reflect.clazz("net.minecraft.world.scores.Scoreboard");
        dummyScoreboard = Reflect.instantiate(Reflect.constructor(scoreboardClass));
        Class<?> criteriaClass = Reflect.clazz("net.minecraft.world.scores.criteria.ObjectiveCriteria");
        dummyCriteria = Reflect.get(Reflect.field(criteriaClass, "DUMMY"), null);
        Class<?> renderTypeClass = Reflect.clazz(
                "net.minecraft.world.scores.criteria.ObjectiveCriteria$RenderType");
        renderTypeInteger = Reflect.enumValue(renderTypeClass, "INTEGER");
        Class<?> objectiveClass = Reflect.clazz("net.minecraft.world.scores.Objective");
        Constructor<?> objCtor;
        int objArgs;
        try {
            objCtor = Reflect.constructorByCount(objectiveClass, 7); // + displayAutoUpdate + numberFormat
            objArgs = 7;
        } catch (RuntimeException e7) {
            objCtor = Reflect.constructorByCount(objectiveClass, 5); // 1.20.2-style
            objArgs = 5;
        }
        objectiveCtor = objCtor;
        objectiveCtorArgs = objArgs;
        blankNumberFormat = resolveBlankNumberFormat();
        Class<?> nmsChatComponent = Reflect.clazz("net.minecraft.network.chat.Component");
        fixedFormatCtor = resolveFormatCtor("net.minecraft.network.chat.numbers.FixedFormat", nmsChatComponent);
        Class<?> nmsStyleClass = tryClass("net.minecraft.network.chat.Style");
        styledFormatCtor = nmsStyleClass == null ? null
                : resolveFormatCtor("net.minecraft.network.chat.numbers.StyledFormat", nmsStyleClass);
        adventureStyleToVanilla = resolvePaperStyleConverter(nmsStyleClass);
        objectiveGetName = Reflect.method(objectiveClass, "getName");
        Class<?> setObjectivePacket = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
        setObjectivePacketCtor = Reflect.constructor(setObjectivePacket, objectiveClass, int.class);

        // --- display slot ---
        displaySlotClass = Reflect.clazz("net.minecraft.world.scores.DisplaySlot");
        Class<?> setDisplayPacket = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket");
        setDisplayPacketCtor = Reflect.constructor(setDisplayPacket, displaySlotClass, objectiveClass);

        // --- scores (1.20.3+) ---
        Class<?> setScorePacket = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundSetScorePacket");
        setScorePacketCtor = Reflect.constructorByCount(setScorePacket, 5); // owner, obj, score, display, numberFormat
        Class<?>[] scoreParams = setScorePacketCtor.getParameterTypes();
        scoreDisplayOptional = scoreParams[3] == java.util.Optional.class;
        scoreFormatOptional = scoreParams[4] == java.util.Optional.class;
        Class<?> resetScorePacket = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundResetScorePacket");
        resetScorePacketCtor = Reflect.constructorByCount(resetScorePacket, 2); // owner, objectiveName
        resetObjectiveOptional = resetScorePacketCtor.getParameterTypes()[1] == java.util.Optional.class;

        // --- teams ---
        playerTeamClass = Reflect.clazz("net.minecraft.world.scores.PlayerTeam");
        playerTeamCtor = Reflect.constructor(playerTeamClass, scoreboardClass, String.class);
        Class<?> nmsComponent = Reflect.clazz("net.minecraft.network.chat.Component");
        chatFormattingClass = Reflect.clazz("net.minecraft.ChatFormatting");
        teamVisibilityClass = Reflect.clazz("net.minecraft.world.scores.Team$Visibility");
        teamCollisionClass = Reflect.clazz("net.minecraft.world.scores.Team$CollisionRule");
        teamSetPrefix = Reflect.method(playerTeamClass, "setPlayerPrefix", nmsComponent);
        teamSetSuffix = Reflect.method(playerTeamClass, "setPlayerSuffix", nmsComponent);
        teamSetDisplayName = Reflect.method(playerTeamClass, "setDisplayName", nmsComponent);
        teamSetColor = Reflect.method(playerTeamClass, "setColor", chatFormattingClass);
        teamSetNametagVisibility = Reflect.method(playerTeamClass, "setNameTagVisibility", teamVisibilityClass);
        teamSetCollision = Reflect.method(playerTeamClass, "setCollisionRule", teamCollisionClass);
        teamSetFriendlyFire = Reflect.method(playerTeamClass, "setAllowFriendlyFire", boolean.class);
        teamSetSeeInvisibles = Reflect.method(playerTeamClass, "setSeeFriendlyInvisibles", boolean.class);
        teamGetPlayers = Reflect.method(playerTeamClass, "getPlayers");
        Class<?> teamPacket = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
        createAddOrModify = Reflect.method(teamPacket, "createAddOrModifyPacket", playerTeamClass, boolean.class);
        createRemove = Reflect.method(teamPacket, "createRemovePacket", playerTeamClass);
        teamActionClass = Reflect.clazz(
                "net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket$Action");
        createPlayerPacket = Reflect.method(teamPacket, "createPlayerPacket",
                playerTeamClass, String.class, teamActionClass);

        initTabSupport(serverPlayer);
    }

    // ---- per-viewer tab names (ClientboundPlayerInfoUpdatePacket, best-effort) ---------------

    private boolean tabNameSupported;
    private Constructor<?> playerInfoCtor;             // (Action, ServerPlayer)
    private Object updateDisplayNameAction;
    private Field playerInfoEntriesField;              // List<Entry>
    private Class<?> nmsComponentClass;
    private Constructor<?> entryCanonicalCtor;
    private java.lang.reflect.RecordComponent[] entryComponents;
    private int displayNameIndex = -1;

    private void initTabSupport(Class<?> serverPlayer) {
        try {
            Class<?> packet = Reflect.clazz("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> actionClass = Reflect.clazz(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            updateDisplayNameAction = Reflect.enumValue(actionClass, "UPDATE_DISPLAY_NAME");
            playerInfoCtor = packet.getDeclaredConstructor(actionClass, serverPlayer);
            playerInfoCtor.setAccessible(true);
            playerInfoEntriesField = Reflect.fieldByTypeDeep(packet, java.util.List.class);
            nmsComponentClass = Reflect.clazz("net.minecraft.network.chat.Component");

            Class<?> entryClass = Reflect.clazz(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            entryComponents = entryClass.getRecordComponents();
            displayNameIndex = locateDisplayName(entryComponents);

            Class<?>[] types = new Class<?>[entryComponents.length];
            for (int i = 0; i < types.length; i++) {
                types[i] = entryComponents[i].getType();
            }
            entryCanonicalCtor = entryClass.getDeclaredConstructor(types);
            entryCanonicalCtor.setAccessible(true);
            tabNameSupported = true;
        } catch (Throwable t) {
            tabNameSupported = false;
        }
    }

    /** Finds the display-name field by name, else by being the single Component-typed field. */
    private int locateDisplayName(java.lang.reflect.RecordComponent[] components) {
        for (int i = 0; i < components.length; i++) {
            if (components[i].getType() == nmsComponentClass && components[i].getName().equals("displayName")) {
                return i;
            }
        }
        int found = -1;
        int count = 0;
        for (int i = 0; i < components.length; i++) {
            if (components[i].getType() == nmsComponentClass) {
                count++;
                found = i;
            }
        }
        if (count != 1) {
            throw new IllegalStateException("Cannot unambiguously locate the tab display-name component "
                    + "(" + count + " Component-typed fields); per-viewer tab names disabled.");
        }
        return found;
    }

    @Override
    public boolean supportsPerViewerTab() {
        return tabNameSupported;
    }

    @Override
    public boolean tabDisplayName(Player viewer, Player target, Component displayName) {
        if (!tabNameSupported) {
            return false;
        }
        try {
            Object targetHandle = craftPlayerGetHandle.invoke(target);
            Object packet = playerInfoCtor.newInstance(updateDisplayNameAction, targetHandle);
            java.util.List<?> entries = (java.util.List<?>) playerInfoEntriesField.get(packet);
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            Object entry = entries.get(0);
            Object nmsName = displayName == null ? null : components.toVanilla(displayName);
            // Rebuild the record, copying every component and swapping only the display-name field.
            Object[] args = new Object[entryComponents.length];
            for (int i = 0; i < entryComponents.length; i++) {
                args[i] = (i == displayNameIndex) ? nmsName : entryComponents[i].getAccessor().invoke(entry);
            }
            Object rebuilt = entryCanonicalCtor.newInstance(args);
            playerInfoEntriesField.set(packet, java.util.Collections.singletonList(rebuilt));
            send(viewer, packet);
            return true;
        } catch (Throwable t) {
            return false; // fail safe: never crash the caller
        }
    }

    private static Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> resolveFormatCtor(String className, Class<?> argType) {
        try {
            Class<?> c = Class.forName(className);
            Constructor<?> ctor = c.getDeclaredConstructor(argType);
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Paper exposes PaperAdventure.asVanilla(Style) on most builds; used for styled number formats. */
    private static Method resolvePaperStyleConverter(Class<?> nmsStyleClass) {
        if (nmsStyleClass == null) {
            return null;
        }
        try {
            Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
            for (Method m : paperAdventure.getDeclaredMethods()) {
                if (m.getName().equals("asVanilla") && m.getParameterCount() == 1
                        && m.getReturnType() == nmsStyleClass) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Converts an API {@link NumberFormat} into an NMS number-format object, or null to inherit. */
    private Object toNmsNumberFormat(NumberFormat format) {
        if (format == null || format instanceof NumberFormat.Default) {
            return null;
        }
        if (format instanceof NumberFormat.Blank) {
            return blankNumberFormat;
        }
        if (format instanceof NumberFormat.Fixed fixed && fixedFormatCtor != null) {
            return Reflect.instantiate(fixedFormatCtor, components.toVanilla(fixed.component()));
        }
        if (format instanceof NumberFormat.Styled styled && styledFormatCtor != null
                && adventureStyleToVanilla != null) {
            try {
                Object nmsStyle = adventureStyleToVanilla.invoke(null, styled.style());
                return Reflect.instantiate(styledFormatCtor, nmsStyle);
            } catch (Throwable t) {
                return null; // fall back to inherit rather than crash
            }
        }
        return null;
    }

    private static Object resolveBlankNumberFormat() {
        // net.minecraft.network.chat.numbers.BlankFormat.INSTANCE hides the red score numbers.
        try {
            Class<?> blank = Class.forName("net.minecraft.network.chat.numbers.BlankFormat");
            Field instance = blank.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            return instance.get(null);
        } catch (Throwable t) {
            return null; // numbers will show; acceptable degradation on unexpected builds
        }
    }

    private static Method findSend(Class<?> listener, Class<?> packetClass) {
        Class<?> c = listener;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(packetClass)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new IllegalStateException("FoliaBoard: could not find connection#send(Packet)");
    }

    @Override
    public String describe() {
        return "NmsPacketAdapter (reflection, Mojang-mapped, 1.20.6+)";
    }

    private volatile net.foliaboard.internal.metrics.PacketMetrics metrics;

    @Override
    public void attachMetrics(net.foliaboard.internal.metrics.PacketMetrics metrics) {
        this.metrics = metrics;
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Diagnostic packet logging. Enable with -Dfoliaboard.debug=true on the server JVM. */
    private static final boolean DEBUG = Boolean.getBoolean("foliaboard.debug");

    private void send(Player viewer, Object packet) {
        net.foliaboard.internal.metrics.PacketMetrics m = metrics;
        if (m != null) {
            m.sent();
        }
        try {
            if (DEBUG) {
                java.util.logging.Logger.getLogger("FoliaBoard")
                        .info("[send] " + packet.getClass().getSimpleName() + " -> " + viewer.getName());
            }
            Object connection;
            if (sendMH != null) {
                Object handle = getHandleMH.invoke(viewer);
                connection = connectionGetterMH.invoke(handle);
                if (connection == null) {
                    return; // player disconnecting
                }
                sendMH.invoke(connection, packet);
            } else {
                Object handle = craftPlayerGetHandle.invoke(viewer);
                connection = connectionField.get(handle);
                if (connection == null) {
                    return; // player disconnecting
                }
                sendMethod.invoke(connection, packet);
            }
        } catch (Throwable t) {
            // Ordinary disconnect race: the player left between our check and the send. This runs in
            // a scheduled entity task, so throwing here spams stack traces during disconnect storms.
            if (!viewer.isOnline()) {
                return;
            }
            throw new IllegalStateException("FoliaBoard: failed to send packet to " + viewer.getName(), t);
        }
    }

    private Object buildObjective(String id, Component title) {
        Object nmsTitle = components.toVanilla(title);
        if (objectiveCtorArgs == 7) {
            // Objective default = vanilla numbers. Sidebars hide numbers per line (blank format);
            // below-name / tab objectives want the numbers visible.
            return Reflect.instantiate(objectiveCtor,
                    dummyScoreboard, id, dummyCriteria, nmsTitle, renderTypeInteger, false, null);
        }
        return Reflect.instantiate(objectiveCtor,
                dummyScoreboard, id, dummyCriteria, nmsTitle, renderTypeInteger);
    }

    // ---- objectives -------------------------------------------------------------------------

    @Override
    public void createObjective(Player viewer, String objectiveId, Component title) {
        send(viewer, Reflect.instantiate(setObjectivePacketCtor, buildObjective(objectiveId, title), 0));
    }

    @Override
    public void updateObjective(Player viewer, String objectiveId, Component title) {
        send(viewer, Reflect.instantiate(setObjectivePacketCtor, buildObjective(objectiveId, title), 2));
    }

    @Override
    public void removeObjective(Player viewer, String objectiveId) {
        send(viewer, Reflect.instantiate(setObjectivePacketCtor,
                buildObjective(objectiveId, Component.empty()), 1));
    }

    @Override
    public void setDisplaySlot(Player viewer, String objectiveId, DisplaySlotType slot) {
        Object nmsSlot = Reflect.enumValue(displaySlotClass, slot.nmsName());
        Object objective = buildObjective(objectiveId, Component.empty());
        send(viewer, Reflect.instantiate(setDisplayPacketCtor, nmsSlot, objective));
    }

    // ---- scores -----------------------------------------------------------------------------

    @Override
    public void setScore(Player viewer, String objectiveId, String entry, int value,
                         Component displayName, NumberFormat numberFormat) {
        Object nmsDisplay = displayName == null ? null : components.toVanilla(displayName);
        Object nmsFormat = toNmsNumberFormat(numberFormat);
        // On 1.20.5+ the record fields are Optional<...>; on older builds they are @Nullable.
        Object displayArg = scoreDisplayOptional ? java.util.Optional.ofNullable(nmsDisplay) : nmsDisplay;
        Object formatArg = scoreFormatOptional ? java.util.Optional.ofNullable(nmsFormat) : nmsFormat;
        send(viewer, Reflect.instantiate(setScorePacketCtor, entry, objectiveId, value, displayArg, formatArg));
    }

    @Override
    public void resetScore(Player viewer, String objectiveId, String entry) {
        Object objectiveArg = resetObjectiveOptional ? java.util.Optional.of(objectiveId) : objectiveId;
        send(viewer, Reflect.instantiate(resetScorePacketCtor, entry, objectiveArg));
    }

    // ---- teams ------------------------------------------------------------------------------

    private Object buildTeam(TeamData data, Collection<String> entries) {
        Object team = Reflect.instantiate(playerTeamCtor, dummyScoreboard, data.name());
        Reflect.invoke(teamSetDisplayName, team, components.toVanilla(data.displayName()));
        Reflect.invoke(teamSetPrefix, team, components.toVanilla(data.prefix()));
        Reflect.invoke(teamSetSuffix, team, components.toVanilla(data.suffix()));
        Reflect.invoke(teamSetNametagVisibility, team,
                Reflect.enumValue(teamVisibilityClass, data.nametagVisibility().nmsName()));
        Reflect.invoke(teamSetCollision, team,
                Reflect.enumValue(teamCollisionClass, data.collision().nmsName()));
        Reflect.invoke(teamSetFriendlyFire, team, data.friendlyFire());
        Reflect.invoke(teamSetSeeInvisibles, team, data.seeFriendlyInvisibles());
        NamedTextColor color = data.color();
        if (color != null) {
            Object chatFormatting = chatFormattingFor(color);
            if (chatFormatting != null) {
                Reflect.invoke(teamSetColor, team, chatFormatting);
            }
        }
        if (entries != null && !entries.isEmpty()) {
            Collection<String> players = Reflect.invoke(teamGetPlayers, team);
            players.addAll(entries);
        }
        return team;
    }

    private Object chatFormattingFor(NamedTextColor color) {
        try {
            return Reflect.enumValue(chatFormattingClass, color.toString().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void createTeam(Player viewer, TeamData team, Collection<String> entries) {
        Object nmsTeam = buildTeam(team, entries);
        send(viewer, Reflect.invoke(createAddOrModify, null, nmsTeam, true));
    }

    @Override
    public void updateTeam(Player viewer, TeamData team) {
        Object nmsTeam = buildTeam(team, null);
        send(viewer, Reflect.invoke(createAddOrModify, null, nmsTeam, false));
    }

    @Override
    public void removeTeam(Player viewer, String teamName) {
        Object nmsTeam = Reflect.instantiate(playerTeamCtor, dummyScoreboard, teamName);
        send(viewer, Reflect.invoke(createRemove, null, nmsTeam));
    }

    @Override
    public void teamEntries(Player viewer, String teamName, Collection<String> entries, boolean add) {
        Object nmsTeam = Reflect.instantiate(playerTeamCtor, dummyScoreboard, teamName);
        Object action = Reflect.enumValue(teamActionClass, add ? "ADD" : "REMOVE");
        for (String entry : entries) {
            send(viewer, Reflect.invoke(createPlayerPacket, null, nmsTeam, entry, action));
        }
    }
}
