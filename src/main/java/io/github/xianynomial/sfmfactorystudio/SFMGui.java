package io.github.xianynomial.sfmfactorystudio;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SFM Factory Studio (1.20.1 Forge port) — a bilingual (Chinese / English) visual
 * programming companion for Super Factory Manager with synchronized block and
 * source editing.
 * <p>
 * This port is client-focused: the editor, pickers, diagnostics and JEI hooks all
 * live on the client. The only networked extra is the optional "pull labels"
 * helper, which degrades silently on servers without the addon.
 * <p>
 * The addon never modifies SFM. It hooks into SFM's manager screen via a client
 * event and reuses SFM's public APIs (program string, packets, DSL parser) to
 * read and save programs.
 */
@Mod(SFMGui.MOD_ID)
public class SFMGui {
    public static final String MOD_ID = "sfmfactorystudio";
    public static final Logger LOGGER = LoggerFactory.getLogger("SFMGui");

    public SFMGui() {
        LOGGER.info("SFM Factory Studio (SFM 智造工坊) 1.20.1 Forge port loaded");
    }
}
