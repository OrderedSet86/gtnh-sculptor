/*
 * Sculptor, an interactive creative world editor for Minecraft 1.7.10
 * Copyright (C) Sculptor contributors
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.gtnh.sculptor.brush;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * How far the pass that runs after a placement brush is allowed to go.
 *
 * <p>
 * Auto-smooth is there to soften the seam where a placed shape meets the ground. Left uncapped it
 * follows the local mean instead, and beside a nineteen-block cylinder that mean is nineteen
 * blocks away -- so a single stroke hollowed out the hillside it was dropped into.
 * </p>
 */
public class AutoSmoothTest {

    private static final int RADIUS = 12;
    private static final int HEIGHT = 19;
    private static final int CENTRE_Y = 85;

    /** The cap in {@code SculptorBrush.apply}: two blocks, or a third of the radius. */
    private static final int EXPECTED_CAP = Math.max(2, Math.round(RADIUS / 3.0F));

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    /**
     * A hillside falling one block per step in x, with three blocks of roughness and voids under
     * it -- a real slope is neither flat nor solid to bedrock.
     */
    private static class SlopeWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int x = position.getBlockX(), z = position.getBlockZ();
            int top = CENTRE_Y - x + (Math.floorMod(hash(x, z), 7) - 3);
            int y = position.getBlockY();

            if (y > top) return new BaseBlock(BlockID.AIR);
            if (Math.floorMod(hash(x + 7, z - 3), 5) == 0 && y <= top - 4 && y >= top - 9) {
                return new BaseBlock(BlockID.AIR);
            }
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static SculptorEditContext stroke(double autoSmooth) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld());
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, autoSmooth, false));
        return ctx;
    }

    private static int surfaceOf(SculptorEditContext ctx, int x, int z) {
        for (int y = 200; y >= 0; y--) {
            if (!ctx.isAir(new Vector(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Measured against the same stroke without the pass, so what is under test is the smoothing
     * and not the cylinder it follows.
     */
    @Test
    public void autoSmoothBlendsTheSeamWithoutReshapingTheHillside() throws Exception {
        SculptorEditContext plain = stroke(0.0D);
        SculptorEditContext smoothed = stroke(1.0D);

        int span = RADIUS + Math.max(3, RADIUS / 2) + 2;
        int worst = 0;
        int moved = 0;

        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int before = surfaceOf(plain, dx, dz);
                int after = surfaceOf(smoothed, dx, dz);
                if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) continue;

                if (after != before) moved++;
                worst = Math.max(worst, Math.abs(after - before));
            }
        }

        assertTrue("auto-smooth moved a column " + worst + " blocks, cap is " + EXPECTED_CAP, worst <= EXPECTED_CAP);

        // The cap must bound the pass, not disable it -- a blend that touches nothing is no
        // better than one that carves out a hillside.
        assertTrue("auto-smooth should still have blended a good number of columns, got " + moved, moved > 200);
    }
}
