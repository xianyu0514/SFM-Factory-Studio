package dev.xianyu.chronosfm.adapter;

import dev.xianyu.chronosfm.model.ProgramModel;

/**
 * Boundary implemented by the SFM integration module.
 *
 * @param <SOURCE> native SFM Program/AST type for the target Minecraft branch
 */
@FunctionalInterface
public interface ProgramModelAdapter<SOURCE> {
    ProgramModel adapt(SOURCE source);
}
