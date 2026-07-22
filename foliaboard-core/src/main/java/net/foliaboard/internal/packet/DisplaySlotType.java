package net.foliaboard.internal.packet;

/**
 * The three vanilla objective display slots FoliaBoard uses.
 * Maps 1:1 onto the NMS {@code DisplaySlot} enum names.
 */
public enum DisplaySlotType {
    /** Right-hand sidebar. */
    SIDEBAR("SIDEBAR"),
    /** Number shown below the player's name in the world. */
    BELOW_NAME("BELOW_NAME"),
    /** Number shown in the tab player list. */
    PLAYER_LIST("LIST");

    private final String nmsName;

    DisplaySlotType(String nmsName) {
        this.nmsName = nmsName;
    }

    public String nmsName() {
        return nmsName;
    }
}
