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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * Whether a placed shape is extended down onto the terrain, and when it is not.
 *
 * <p>
 * The distinction is resting on the landscape versus being built above it, and it must not be
 * approximated by how far down the ground is. A distance limit was the first attempt: at twice the
 * radius, it meant the same hillside that worked with a small brush went back to floating with a
 * large one, at 24 blocks of drop, then 32, then 40.
 * </p>
 */
public class SettleOntoGroundTest {

    private static final int CENTRE_Y = 120;
    private static final int HEIGHT = 10;

    /** Ground level with the brush on one side, and a sheer drop on the other. */
    private static class CliffWorld extends NullWorld {

        private final int drop;

        CliffWorld(int drop) {
            this.drop = drop;
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            int top = (position.getBlockX() <= 0) ? CENTRE_Y : CENTRE_Y - drop;
            int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    /** Flat ground far below the brush, so no part of the shape touches anything. */
    private static class DistantGroundWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int y = position.getBlockY();
            if (y > CENTRE_Y - 60) return new BaseBlock(BlockID.AIR);
            if (y == CENTRE_Y - 60) return new BaseBlock(BlockID.GRASS);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static SculptorEditContext cylinder(NullWorld world, int radius, double autoSmooth) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(world);
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            new BrushParams(radius, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, autoSmooth, false));
        return ctx;
    }

    /** Columns whose topmost run of solid blocks does not reach anything below it. */
    private static int columnsLeftHanging(SculptorEditContext ctx, int radius) {
        int hanging = 0;
        for (int dx = -radius - 2; dx <= radius + 2; dx++) {
            for (int dz = -radius - 2; dz <= radius + 2; dz++) {
                int y = 250;
                while (y >= 0 && ctx.isAir(new Vector(dx, y, dz))) y--;
                if (y < 0) continue;
                while (y >= 0 && !ctx.isAir(new Vector(dx, y, dz))) y--;
                if (y < 0) continue;

                boolean gap = false;
                while (y >= 0 && ctx.isAir(new Vector(dx, y, dz))) {
                    gap = true;
                    y--;
                }
                if (gap && y >= 0) hanging++;
            }
        }
        return hanging;
    }

    @Test
    public void aShapeRestingOnACliffEdgeFollowsTheDropHoweverDeepItIs() throws Exception {
        for (int radius : new int[] { 8, 12, 16, 20 }) {
            for (int drop : new int[] { 10, 24, 32, 40, 60 }) {
                int hanging = columnsLeftHanging(cylinder(new CliffWorld(drop), radius, 1.0D), radius);
                assertEquals("radius " + radius + " over a " + drop + "-block drop left columns hanging", 0, hanging);
            }
        }
    }

    /**
     * Nothing in the footprint touches the terrain, so the shape was built above the landscape
     * rather than placed on it, and is left exactly as it was drawn.
     */
    @Test
    public void aShapeBuiltInOpenSkyIsNotDraggedDownToTheGround() throws Exception {
        int radius = 12;
        int hanging = columnsLeftHanging(cylinder(new DistantGroundWorld(), radius, 1.0D), radius);

        assertTrue("a cylinder in open sky should still be standing in open sky, got " + hanging, hanging > 100);
    }

    @Test
    public void withAutoSmoothOffTheShapeKeepsItsOwnGeometry() throws Exception {
        int radius = 12;
        int hanging = columnsLeftHanging(cylinder(new CliffWorld(40), radius, 0.0D), radius);

        assertTrue("with the slider at zero a cylinder is a cylinder, got " + hanging, hanging > 100);
    }
}
