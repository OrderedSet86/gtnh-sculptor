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
 * What the blending pass may reach.
 *
 * <p>
 * A height map spans a vertical face as though it were a slope, so ground at the foot of a tower
 * is only a column or two away from the tower's top as far as the mean is concerned. Left alone
 * it climbs to meet a shape placed forty blocks overhead -- a ring of raised earth around a leg
 * whose top the brush never came near.
 * </p>
 */
public class DistantGroundTest {

    private static final int GROUND = 64;
    private static final int LEG_TOP = 110;
    private static final int RADIUS = 10;
    private static final int HEIGHT = 8;

    /** A thin leg rising out of flat ground, the way an arch's support does. */
    private static class LegWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int x = position.getBlockX(), z = position.getBlockZ(), y = position.getBlockY();
            boolean leg = x * x + z * z <= 36 && y <= LEG_TOP;
            return new BaseBlock((y <= GROUND || leg) ? BlockID.STONE : BlockID.AIR);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static int topOf(SculptorEditContext ctx, int x, int z) {
        int y = 200;
        while (y >= 0 && ctx.isAir(new Vector(x, y, z))) y--;
        return y;
    }

    /** Columns of the flat ground whose surface the stroke moved. */
    private static int groundColumnsMoved(int brushY) throws Exception {
        SculptorEditContext plain = SculptorEditContext.preview(new LegWorld());
        SculptorEditContext shaped = SculptorEditContext.preview(new LegWorld());

        Vector at = new Vector(0, brushY, 0);
        SculptorBrush.CYLINDER.apply(
            plain,
            at,
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, 0.0D, false, OverhangMode.CANTILEVER));
        SculptorBrush.CYLINDER.apply(
            shaped,
            at,
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, 1.0D, false, OverhangMode.CANTILEVER));

        int reach = RADIUS + Math.max(3, RADIUS / 2);
        int moved = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int before = topOf(plain, dx, dz);
                // Only the flat ground, never the leg.
                if (before > GROUND + 2) continue;
                if (topOf(shaped, dx, dz) != before) moved++;
            }
        }
        return moved;
    }

    @Test
    public void groundFarBelowTheShapeIsLeftAlone() throws Exception {
        assertEquals("ground at the foot of the leg rose to meet a shape at its top", 0, groundColumnsMoved(LEG_TOP));
    }

    /**
     * The control. A floor that switched blending off everywhere would pass the test above while
     * doing nothing useful, so the same brush placed near the ground must still blend into it.
     *
     * <p>
     * Placed a few blocks clear of the ground rather than exactly on it. Sitting flush, the
     * ground is never below the shape's base, so a floor set anywhere at or under that base looks
     * identical to the right one and the control proves nothing.
     * </p>
     */
    @Test
    public void groundTheShapeIsActuallySittingOnIsStillBlended() throws Exception {
        assertTrue(
            "a shape placed just above the ground should still blend into it",
            groundColumnsMoved(GROUND + 4) > 0);
    }
}
