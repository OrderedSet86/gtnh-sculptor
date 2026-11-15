package com.gtnh.sculptor.brush;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * A setting row of the editor panel is shown only when the setting changes a stroke. Each test checks the predicate of
 * a row against strokes with the setting at each of the two values, on terrain from seeds of a seeded {@link Random}.
 * {@link SculptorBrush#TREE} is left out: a tree generator uses the biome and block registries of Minecraft.
 */
public class SettingEffectTest {

    private static final int TERRAINS = 20;
    private static final int GROUND_Y = 63;
    // a block that the terrain does not have, so a write of the material differs from a write of a terrain block
    private static final BaseBlock MATERIAL = new BaseBlock(BlockID.CLOTH, 14);
    private static final BaseBlock AIR = new BaseBlock(BlockID.AIR);

    @Test
    public void theNewBlockModeChangesTheStrokeOnlyForBrushesWithTheFlag() throws Exception {
        for (final SculptorBrush brush : SculptorBrush.values()) {
            if (brush == SculptorBrush.TREE) continue;
            assertEquals(
                brush.name(),
                brush.hasNewBlockMode(),
                isChangedBySetting(brush, createParams(MATERIAL, 0.0D), NEW_BLOCKS_FROM_MATERIAL));
        }
    }

    @Test
    public void theNewBlockModeChangesTheAutoSmoothPass() throws Exception {
        for (final SculptorBrush brush : SculptorBrush.values()) {
            if (!brush.isAutoSmoothable()) continue;
            assertEquals(
                brush.name(),
                true,
                isChangedBySetting(brush, createParams(MATERIAL, 0.5D), NEW_BLOCKS_FROM_MATERIAL));
        }
    }

    @Test
    public void theOverhangModeChangesTheStrokeExactlyWhenUsesOverhangIsTrue() throws Exception {
        for (final SculptorBrush brush : SculptorBrush.values()) {
            if (brush == SculptorBrush.TREE) continue;
            for (final BaseBlock material : new BaseBlock[] { MATERIAL, AIR }) {
                for (final double autoSmooth : new double[] { 0.0D, 0.5D }) {
                    assertEquals(
                        brush.name() + " material=" + material.getType() + " autoSmooth=" + autoSmooth,
                        brush.usesOverhang(material.getType(), autoSmooth),
                        isChangedBySetting(brush, createParams(material, autoSmooth), CANTILEVER));
                }
            }
        }
    }

    private static final UnaryOperator<BrushParams> NEW_BLOCKS_FROM_MATERIAL = params -> params.toBuilder()
        .useSelectedMaterial(true)
        .build();
    private static final UnaryOperator<BrushParams> CANTILEVER = params -> params.toBuilder()
        .overhang(OverhangMode.CANTILEVER)
        .build();

    private static BrushParams createParams(BaseBlock material, double autoSmooth) {
        return BrushParams.builder()
            .radius(4)
            .material(material)
            .secondary(2)
            .strength(1.0D)
            .autoSmooth(autoSmooth)
            .build();
    }

    // true when the other value of the setting changes the result on at least one terrain
    private static boolean isChangedBySetting(SculptorBrush brush, BrushParams params,
        UnaryOperator<BrushParams> otherValue) throws Exception {
        final Random random = new Random(47L);
        for (int run = 0; run < TERRAINS; run++) {
            final RoughWorld world = new RoughWorld(random.nextLong());
            if (!runStroke(world, brush, params).equals(runStroke(world, brush, otherValue.apply(params)))) return true;
        }
        return false;
    }

    private static Map<BlockVector, BaseBlock> runStroke(RoughWorld world, SculptorBrush brush, BrushParams params)
        throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(world);
        brush.apply(ctx, new Vector(0, GROUND_Y, 0), params);
        return ctx.getPendingChanges();
    }

    /** Grass over 3 blocks of dirt over stone, with the grass up to 4 blocks above or below {@link #GROUND_Y}. */
    private static final class RoughWorld extends NullWorld {

        private final long seed;

        private RoughWorld(long seed) {
            this.seed = seed;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final long column = seed ^ (position.getBlockX() * 73_856_093L) ^ (position.getBlockZ() * 83_492_791L);
            final int surface = GROUND_Y - 4 + new Random(column).nextInt(9);
            final int y = position.getBlockY();
            if (y > surface) return new BaseBlock(BlockID.AIR);
            if (y == surface) return new BaseBlock(BlockID.GRASS);
            if (y >= surface - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }
}
