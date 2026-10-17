package dev.tntcoating.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Values read from the four explicit XaeroPlus caches; an absent highlight is not a negative result. */
record XaeroPlusChunkClassification(boolean old112, boolean modern, boolean nonFresh, boolean fresh) {
    boolean known() { return old112 || modern || nonFresh || fresh; }

    boolean selectedPositive(XaeroPlusBridge.Detection detection) {
        return (detection != XaeroPlusBridge.Detection.NON_FRESH && old112)
            || (detection != XaeroPlusBridge.Detection.OLD_112 && nonFresh);
    }

    boolean selectedComplete(XaeroPlusBridge.Detection detection, boolean oldEnabled, boolean paletteEnabled) {
        return (!oldEnabled || detection == XaeroPlusBridge.Detection.NON_FRESH || old112 || modern)
            && (!paletteEnabled || detection == XaeroPlusBridge.Detection.OLD_112 || nonFresh || fresh);
    }

    XaeroPlusBridge.Chunk chunk(int x, int z, ResourceKey<Level> dimension,
                               XaeroPlusBridge.Detection detection, long receivedAtMillis) {
        if (!known()) return null;
        return new XaeroPlusBridge.Chunk(x, z, dimension,
            detection != XaeroPlusBridge.Detection.NON_FRESH && old112,
            detection != XaeroPlusBridge.Detection.OLD_112 && nonFresh,
            fresh, receivedAtMillis, detection != XaeroPlusBridge.Detection.NON_FRESH && modern);
    }
}
