package ca.teamdman.sfmjimu;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SFM Factory Studio — a Chinese visual programming companion for Super Factory
 * Manager with synchronized block and source editing.
 * <p>
 * The addon never modifies SFM. It hooks into SFM's manager screen via a client
 * event and reuses SFM's public APIs (program string, packets, DSL parser) to
 * read and save programs.
 */
@Mod(SFMGui.MOD_ID)
public class SFMGui {
    public static final String MOD_ID = "sfmjimu";
    public static final Logger LOGGER = LoggerFactory.getLogger("SFMGui");

    public SFMGui(IEventBus modEventBus) {
        LOGGER.info("SFM Factory Studio (SFM 智造工坊) loaded");
    }
}
