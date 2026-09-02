package ca.teamdman.sfmjimu.net;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capabilities advertised by the (optional) SFM fork on this server, pushed
 * once at login via {@link SfmCapabilitiesPayload}. Defaults to everything
 * OFF so vanilla-SFM servers simply never unlock the gated features.
 */
public final class SfmCaps {
    private static volatile Set<String> caps = Set.of();

    private SfmCaps() {
    }

    public static void accept(Set<String> capabilities) {
        caps = Set.copyOf(capabilities);
    }

    public static void reset() {
        caps = Set.of();
    }

    /** True only when the connected server runs an SFM fork with component matching. */
    public static boolean withComponent() {
        return caps.contains("with_component");
    }
}
