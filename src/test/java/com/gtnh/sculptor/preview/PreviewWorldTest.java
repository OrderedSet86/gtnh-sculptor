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

package com.gtnh.sculptor.preview;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * Where a pending change is found, and where it is not.
 *
 * <p>
 * Metadata is the thing under test throughout, because it is the one query that resolves without
 * the block registry -- which is not bootstrapped headlessly. A pending position reports its own
 * data value and everything else falls through to the world, so the two are easy to tell apart.
 * </p>
 */
public class PreviewWorldTest {

    /** Anything reading through to the world gets this, so a fall-through is unmistakable. */
    private static final int DELEGATE_META = 9;

    private static class StubWorld implements IBlockAccess {

        @Override
        public Block getBlock(int x, int y, int z) {
            return null;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minBlockLight) {
            return 0;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return DELEGATE_META;
        }

        @Override
        public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
            return 0;
        }

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            return true;
        }

        @Override
        public BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return null;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public boolean extendedLevelsInChunkCache() {
            return false;
        }

        @Override
        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean defaultValue) {
            return defaultValue;
        }
    }

    private static Map<BlockVector, BaseBlock> changes(int[][] positionsAndMeta) {
        Map<BlockVector, BaseBlock> pending = new LinkedHashMap<BlockVector, BaseBlock>();
        for (int[] entry : positionsAndMeta) {
            pending.put(new BlockVector(entry[0], entry[1], entry[2]), new BaseBlock(BlockID.STONE, entry[3]));
        }
        return pending;
    }

    /**
     * The change set is unpacked into an array over its bounding box, so the index arithmetic has
     * to survive negative coordinates -- which is where an off-by-one reads a neighbour's block
     * and draws the ghost wrong.
     *
     * <p>
     * The bounding box is deliberately 8 x 5 x 13. Equal dimensions would let the x and z strides
     * be swapped without any test noticing, which is exactly the mistake worth catching.
     * </p>
     */
    @Test
    public void findsPendingBlocksAcrossTheOriginAndFallsThroughEverywhereElse() {
        Map<BlockVector, BaseBlock> pending = changes(
            new int[][] { { -5, 60, -3, 1 }, { 2, 64, 9, 2 }, { -5, 64, 9, 3 }, { 2, 60, -3, 4 } });
        PreviewWorld world = new PreviewWorld(new StubWorld(), pending);

        assertEquals(1, world.getBlockMetadata(-5, 60, -3));
        assertEquals(2, world.getBlockMetadata(2, 64, 9));
        assertEquals(3, world.getBlockMetadata(-5, 64, 9));
        assertEquals(4, world.getBlockMetadata(2, 60, -3));

        // Inside the bounding box, but untouched.
        assertEquals(DELEGATE_META, world.getBlockMetadata(0, 62, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 60, 9));
        assertEquals(DELEGATE_META, world.getBlockMetadata(2, 64, -3));

        // Just outside it, on every face.
        assertEquals(DELEGATE_META, world.getBlockMetadata(-6, 60, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(3, 64, 9));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 59, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 65, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 60, -4));
        assertEquals(DELEGATE_META, world.getBlockMetadata(2, 64, 10));

        // Nowhere near.
        assertEquals(DELEGATE_META, world.getBlockMetadata(400, 70, -400));
    }

    /**
     * Two changes far apart would give the array a bounding box of billions of cells, so it is
     * not built and the map answers instead. The answers must not depend on which path ran.
     */
    @Test
    public void aChangeSetTooSpreadOutToUnpackStillResolves() {
        Map<BlockVector, BaseBlock> pending = changes(new int[][] { { 0, 64, 0, 5 }, { 30000, 200, -30000, 6 } });
        PreviewWorld world = new PreviewWorld(new StubWorld(), pending);

        assertEquals(5, world.getBlockMetadata(0, 64, 0));
        assertEquals(6, world.getBlockMetadata(30000, 200, -30000));
        assertEquals(DELEGATE_META, world.getBlockMetadata(1, 64, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(15000, 100, -15000));
    }

    @Test
    public void anEmptyChangeSetReadsEntirelyThroughToTheWorld() {
        PreviewWorld world = new PreviewWorld(new StubWorld(), new LinkedHashMap<BlockVector, BaseBlock>());

        assertEquals(DELEGATE_META, world.getBlockMetadata(0, 0, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-12, 200, 77));
    }
}
