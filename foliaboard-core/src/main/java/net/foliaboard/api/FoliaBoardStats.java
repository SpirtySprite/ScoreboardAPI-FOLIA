package net.foliaboard.api;

/**
 * A point-in-time snapshot of FoliaBoard activity, for profiling TPS impact. Obtain via
 * {@code foliaBoard.stats()}.
 *
 * @param totalPackets      total scoreboard/team packets sent since startup
 * @param providerRefreshes global-provider/builder refresh passes run
 * @param activeSidebars    sidebars currently held
 * @param activeNametags    nametags currently held
 */
public record FoliaBoardStats(long totalPackets, long providerRefreshes,
                              int activeSidebars, int activeNametags) {

    @Override
    public String toString() {
        return "FoliaBoardStats[packets=" + totalPackets + ", refreshes=" + providerRefreshes
                + ", sidebars=" + activeSidebars + ", nametags=" + activeNametags + "]";
    }
}
