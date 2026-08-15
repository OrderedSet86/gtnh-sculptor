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

import java.util.Map;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * Brush behaviour against terrain lifted out of a real saved world.
 *
 * <p>
 * Both cases here were reported from play and neither could be reproduced on hand-built terrain.
 * That is the point of recording it: the shapes that break these brushes -- an undercut, a ledge
 * overhead, a face too rough to think of modelling -- are the ones nobody builds a test world out
 * of.
 * </p>
 */
public class RealTerrainTest {

    private static final int RADIUS = 6;
    private static final int HEIGHT = 8;

    private static SculptorEditContext stroke(String terrain, Vector at, OverhangMode mode, double autoSmooth)
        throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(RecordedTerrain.named(terrain));
        SculptorBrush.CYLINDER.apply(
            ctx,
            at,
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, autoSmooth, false, mode));
        return ctx;
    }

    private static int terrainBlocksDeleted(RecordedTerrain world, SculptorEditContext ctx) {
        int deleted = 0;
        for (Map.Entry<BlockVector, BaseBlock> change : ctx.getPendingChanges()
            .entrySet()) {
            if (change.getValue()
                .getType() != BlockID.AIR) continue;

            BlockVector at = change.getKey();
            if (world.solid(at.getBlockX(), at.getBlockY(), at.getBlockZ())) deleted++;
        }
        return deleted;
    }

    /**
     * The cantilever keeps the shape you drew, so it has no business cutting the landscape around
     * it to meet that shape.
     *
     * <p>
     * On a rough cliff face the blending pass was trimming every bump within its reach by a block
     * or two -- over a hundred blocks for one stroke, scattered, none of them near the shape, and
     * with nothing placed below the cylinder to explain them.
     * </p>
     */
    @Test
    public void theCantileverNeverDeletesTerrainThatWasAlreadyThere() throws Exception {
        String[] places = { "noBottomSmoothing", "strayRemovals" };
        int[][] centres = { { 907, 40, -623 }, { 904, 43, -623 } };

        for (int i = 0; i < places.length; i++) {
            RecordedTerrain world = RecordedTerrain.named(places[i]);
            Vector at = new Vector(centres[i][0], centres[i][1], centres[i][2]);

            SculptorEditContext ctx = stroke(places[i], at, OverhangMode.CANTILEVER, 1.0D);

            assertTrue(
                "nothing was placed at " + places[i] + ", so this proves nothing",
                ctx.getPendingChanges()
                    .size() > 100);
            assertEquals("cantilever deleted terrain at " + places[i], 0, terrainBlocksDeleted(world, ctx));
        }
    }

    /**
     * Filling to ground is a different bargain: there the shape is meant to become part of the
     * hill, so reshaping the ground around it is the point and it is allowed to cut.
     */
    @Test
    public void fillingToGroundIsStillAllowedToReshapeTheGround() throws Exception {
        RecordedTerrain world = RecordedTerrain.named("strayRemovals");
        Vector at = new Vector(904, 43, -623);

        SculptorEditContext ctx = stroke("strayRemovals", at, OverhangMode.SETTLE, 1.0D);
        assertTrue("fill-to-ground should still be able to cut into terrain", terrainBlocksDeleted(world, ctx) > 0);
    }
}
