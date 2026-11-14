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
 * The positions where {@link PreviewWorld} reads a change, and the positions where {@link PreviewWorld} reads the
 * world.
 *
 * <p>
 * The tests read metadata: the one query with no use of the block registry. A headless test has no block registry. A
 * changed position reads the data value of the change, and the world reads {@code DELEGATE_META}.
 * </p>
 */
public class PreviewWorldTest {

    private static final int DELEGATE_META = 9;

    /**
     * The changes go into an array over their bounding box, which here spans negative coordinates. An off-by-one in the
     * index reads the block of a neighbour.
     *
     * <p>
     * The bounding box is 8 by 5 by 13. With equal sizes, a swap of the x and z strides passes the test.
     * </p>
     */
    @Test
    public void findsPendingBlocksAcrossTheOriginAndFallsThroughEverywhereElse() {
        final Map<BlockVector, BaseBlock> pending = createChanges(
            new int[][] { { -5, 60, -3, 1 }, { 2, 64, 9, 2 }, { -5, 64, 9, 3 }, { 2, 60, -3, 4 } });
        final PreviewWorld world = new PreviewWorld(new StubWorld(), pending);

        assertEquals(1, world.getBlockMetadata(-5, 60, -3));
        assertEquals(2, world.getBlockMetadata(2, 64, 9));
        assertEquals(3, world.getBlockMetadata(-5, 64, 9));
        assertEquals(4, world.getBlockMetadata(2, 60, -3));

        // inside the bounding box, with no change
        assertEquals(DELEGATE_META, world.getBlockMetadata(0, 62, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 60, 9));
        assertEquals(DELEGATE_META, world.getBlockMetadata(2, 64, -3));

        // one block outside the bounding box, on each face
        assertEquals(DELEGATE_META, world.getBlockMetadata(-6, 60, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(3, 64, 9));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 59, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 65, -3));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-5, 60, -4));
        assertEquals(DELEGATE_META, world.getBlockMetadata(2, 64, 10));

        // far outside the bounding box
        assertEquals(DELEGATE_META, world.getBlockMetadata(400, 70, -400));
    }

    /**
     * Two changes far apart have a bounding box of billions of cells, so {@link PreviewWorld} reads the map and builds
     * no array. The reads are the same as through the array.
     */
    @Test
    public void changesTooSpreadOutForTheArrayStillResolve() {
        final Map<BlockVector, BaseBlock> pending = createChanges(
            new int[][] { { 0, 64, 0, 5 }, { 30000, 200, -30000, 6 } });
        final PreviewWorld world = new PreviewWorld(new StubWorld(), pending);

        assertEquals(5, world.getBlockMetadata(0, 64, 0));
        assertEquals(6, world.getBlockMetadata(30000, 200, -30000));
        assertEquals(DELEGATE_META, world.getBlockMetadata(1, 64, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(15000, 100, -15000));
    }

    @Test
    public void emptyChangesReadTheWorldEverywhere() {
        final PreviewWorld world = new PreviewWorld(new StubWorld(), new LinkedHashMap<>());

        assertEquals(DELEGATE_META, world.getBlockMetadata(0, 0, 0));
        assertEquals(DELEGATE_META, world.getBlockMetadata(-12, 200, 77));
    }

    // each row is x, y, z and the data value of a stone block
    private static Map<BlockVector, BaseBlock> createChanges(int[][] positionsAndMeta) {
        final Map<BlockVector, BaseBlock> pending = new LinkedHashMap<>();
        for (int[] entry : positionsAndMeta) {
            pending.put(new BlockVector(entry[0], entry[1], entry[2]), new BaseBlock(BlockID.STONE, entry[3]));
        }
        return pending;
    }

    /** A world with no blocks, whose metadata is {@link #DELEGATE_META} everywhere. */
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
}
