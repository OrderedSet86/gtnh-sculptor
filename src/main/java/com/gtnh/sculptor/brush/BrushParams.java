package com.gtnh.sculptor.brush;

import java.util.Objects;

import com.github.bsideup.jabel.Desugar;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The settings of one brush stroke. The settings go to the server in the commit packet, so the preview and the commit
 * run the brush with the same settings.
 *
 * @param secondary           the second parameter of the brush, such as the height of a cylinder or the iterations of
 *                            smooth
 * @param strength            from 0 to 1, the force of a terrain brush
 * @param autoSmooth          from 0 to 1, the strength of the smoothing pass after a placement brush. At 0 the pass
 *                            does not run
 * @param useSelectedMaterial build with {@code material}, not with the block that forms the surface
 */
@Desugar
public record BrushParams(int radius, BaseBlock material, int secondary, double strength, double autoSmooth,
    boolean useSelectedMaterial, OverhangMode overhang) {

    public BrushParams {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(overhang, "overhang");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().radius(radius)
            .material(material)
            .secondary(secondary)
            .strength(strength)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(useSelectedMaterial)
            .overhang(overhang);
    }

    /** A builder that starts from radius 1, stone, no auto-smooth and {@link OverhangMode#SETTLE}. */
    public static final class Builder {

        private int radius = 1;
        private BaseBlock material = new BaseBlock(BlockID.STONE);
        private int secondary = 1;
        private double strength = 0.5D;
        private double autoSmooth;
        private boolean useSelectedMaterial;
        private OverhangMode overhang = OverhangMode.SETTLE;

        private Builder() {}

        public Builder radius(int value) {
            radius = value;
            return this;
        }

        public Builder material(BaseBlock value) {
            material = value;
            return this;
        }

        public Builder secondary(int value) {
            secondary = value;
            return this;
        }

        public Builder strength(double value) {
            strength = value;
            return this;
        }

        public Builder autoSmooth(double value) {
            autoSmooth = value;
            return this;
        }

        public Builder useSelectedMaterial(boolean value) {
            useSelectedMaterial = value;
            return this;
        }

        public Builder overhang(OverhangMode value) {
            overhang = value;
            return this;
        }

        public BrushParams build() {
            return new BrushParams(radius, material, secondary, strength, autoSmooth, useSelectedMaterial, overhang);
        }
    }
}
