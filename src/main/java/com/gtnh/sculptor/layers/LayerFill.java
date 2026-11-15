package com.gtnh.sculptor.layers;

import java.util.List;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.math.noise.PerlinNoise;
import com.sk89q.worldedit.regions.Region;

/**
 * Replace each block in a region, except air, with the block of the layer at the block's altitude. {@code top} and
 * {@code bottom} of the {@link LayerBands} are the Y of the highest and of the lowest block in the region, except air.
 * Without height variation, the bands are the same in every column.
 */
public final class LayerFill {

    public static final int MAX_HEIGHT_VARIATION = 32;

    // the spacing of the noise lattice, in blocks. With a wider spacing, the waves of a band edge are longer
    private static final double NOISE_LATTICE_BLOCKS = 16.0D;

    private final LayerStack stack;
    private final int heightVariation;
    private final int seed;

    /**
     * @param heightVariation from 0 to {@link #MAX_HEIGHT_VARIATION}, the most rows that a band edge moves up or down
     * @param seed            the seed of the noise of edge 0. Edge {@code i} has seed {@code seed + i}
     */
    public LayerFill(LayerStack stack, int heightVariation, int seed) {
        this.stack = stack;
        this.heightVariation = heightVariation;
        this.seed = seed;
    }

    /**
     * Write the layers through {@code edit}. The mask and the block limit of {@code edit} apply to each write. A region
     * of air only gets no write.
     */
    public void apply(EditSession edit, Region region) throws MaxChangedBlocksException {
        final Vector min = region.getMinimumPoint();
        final Vector max = region.getMaximumPoint();

        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                    final Vector position = new Vector(x, y, z);
                    if (!region.contains(position) || edit.getBlockType(position) == BlockID.AIR) continue;
                    top = Math.max(top, y);
                    bottom = Math.min(bottom, y);
                }
            }
        }
        if (top == Integer.MIN_VALUE) return;

        final LayerBands bands = new LayerBands(stack, top, bottom);
        final PerlinNoise[] edgeNoise = createEdgeNoise(bands.getEdgeCount());
        final List<LayerStack.Layer> layers = stack.layers();
        final int[] edgeOffsets = new int[edgeNoise.length];
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                computeEdgeOffsets(edgeNoise, new Vector2D(x, z), edgeOffsets);

                for (int y = bottom; y <= top; y++) {
                    final Vector position = new Vector(x, y, z);
                    if (!region.contains(position) || edit.getBlockType(position) == BlockID.AIR) continue;
                    final int layer = bands.layerAt(y, edgeOffsets);
                    if (layer >= 0) edit.setBlock(
                        position,
                        layers.get(layer)
                            .block());
                }
            }
        }
    }

    private PerlinNoise[] createEdgeNoise(int edgeCount) {
        final PerlinNoise[] edgeNoise = new PerlinNoise[edgeCount];
        for (int edge = 0; edge < edgeCount; edge++) {
            edgeNoise[edge] = new PerlinNoise();
            edgeNoise[edge].setSeed(seed + edge);
            edgeNoise[edge].setFrequency(1.0D / NOISE_LATTICE_BLOCKS);
            edgeNoise[edge].setOctaveCount(1);
        }
        return edgeNoise;
    }

    private void computeEdgeOffsets(PerlinNoise[] edgeNoise, Vector2D column, int[] edgeOffsets) {
        if (heightVariation == 0) return;
        for (int edge = 0; edge < edgeOffsets.length; edge++) {
            // JLibNoiseGenerator clamps the output of the noise to [0, 1]
            final double wave = 2.0D * edgeNoise[edge].noise(column) - 1.0D;
            edgeOffsets[edge] = (int) Math.round(heightVariation * wave);
        }
    }
}
