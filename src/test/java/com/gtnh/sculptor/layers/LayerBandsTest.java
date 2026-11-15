package com.gtnh.sculptor.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * The rules of {@link LayerBands}, over stacks from a seeded {@link Random} and over the boundary heights: one row, and
 * a column of the sum of the counts.
 */
public class LayerBandsTest {

    private static final int CASES = 2000;
    private static final int BOTTOM = 40;

    @Test
    public void eachFixedLayerGetsItsCountWhenTheColumnIsTallEnough() {
        final Random random = new Random(7L);
        for (int run = 0; run < CASES; run++) {
            final Stack stack = generateStack(random);
            final int height = stack.fixedSum() + random.nextInt(12);
            checkCounts(stack, height);
        }
    }

    @Test
    public void countsHoldAtTheBoundaryHeights() {
        checkCounts(new Stack(new int[] { 1 }, -1), 1);
        checkCounts(new Stack(new int[] { 0 }, 0), 1);
        checkCounts(new Stack(new int[] { 1, 0, 1 }, 1), 2);
        checkCounts(new Stack(new int[] { 3, 1, 2 }, -1), 6);
        checkCounts(new Stack(new int[] { 2, 0, 4 }, 1), 6);
    }

    @Test
    public void layersRunTopToBottomWithUnchangedRowsOnlyAtTheBottom() {
        final Random random = new Random(11L);
        for (int run = 0; run < CASES; run++) {
            final Stack stack = generateStack(random);
            final int height = 1 + random.nextInt(stack.fixedSum() + 12);
            final int[] offsets = new int[stack.edgeCount()];
            for (int edge = 0; edge < offsets.length; edge++) offsets[edge] = random.nextInt(21) - 10;
            final LayerBands bands = stack.bands(height);

            int previous = 0;
            boolean unchangedSeen = false;
            for (int y = BOTTOM + height - 1; y >= BOTTOM; y--) {
                final int layer = bands.layerAt(y, offsets);
                final String where = stack + " height=" + height + " offsets=" + Arrays.toString(offsets) + " y=" + y;
                if (layer < 0) {
                    unchangedSeen = true;
                    continue;
                }
                assertTrue("a layer below unchanged rows: " + where, !unchangedSeen);
                assertTrue("layer order reversed: " + where, layer >= previous);
                previous = layer;
            }
            if (stack.restIndex() >= 0) assertTrue("a rest layer leaves no row unchanged: " + stack, !unchangedSeen);
        }
    }

    @Test
    public void whereCountsOverlapTheUpperLayersWin() {
        final Random random = new Random(13L);
        for (int run = 0; run < CASES; run++) {
            final Stack stack = generateStack(random);
            if (stack.fixedSum() < 2) continue;
            final int height = 1 + random.nextInt(stack.fixedSum() - 1);
            final int[] rows = countRows(stack, height, new int[stack.edgeCount()]);

            final int firstFixed = (stack.restIndex() == 0) ? 1 : 0;
            assertEquals(
                "the top row is not the first fixed layer: " + stack + " height=" + height,
                firstFixed,
                stack.bands(height)
                    .layerAt(BOTTOM + height - 1, new int[stack.edgeCount()]));
            boolean shortSeen = false;
            for (int layer = 0; layer < stack.counts().length; layer++) {
                if (layer == stack.restIndex()) continue;
                if (shortSeen) assertEquals("a layer below a cut layer got rows: " + stack, 0, rows[layer]);
                if (rows[layer] < stack.counts()[layer]) shortSeen = true;
            }
        }
    }

    @Test
    public void anOffsetMovesOneEdgeByItsRows() {
        final Random random = new Random(17L);
        for (int run = 0; run < CASES; run++) {
            final Stack stack = generateStack(random);
            final int edgeCount = stack.edgeCount();
            if (edgeCount == 0) continue;

            // a gap of at least 10 rows on each side of every edge, so a move of up to 9 rows passes no other edge
            final int[] counts = stack.counts()
                .clone();
            for (int layer = 0; layer < counts.length; layer++) {
                if (layer != stack.restIndex()) counts[layer] += 10;
            }
            final Stack spaced = new Stack(counts, stack.restIndex());
            final int height = spaced.fixedSum() + 10 + (spaced.restIndex() >= 0 ? 10 : 0);
            final int edge = random.nextInt(edgeCount);
            final int offset = random.nextInt(19) - 9;
            final int[] offsets = new int[edgeCount];
            offsets[edge] = offset;

            final int[] before = countRows(spaced, height, new int[edgeCount]);
            final int[] after = countRows(spaced, height, offsets);
            final String where = spaced + " edge=" + edge + " offset=" + offset;

            // edge i is the lowest row of layer i. An edge up by N: layer i -N, and the next layer, or the unchanged
            // rows below the last layer, +N
            assertEquals("layer above the edge: " + where, before[edge] - offset, after[edge]);
            assertEquals("layer below the edge: " + where, before[edge + 1] + offset, after[edge + 1]);
        }
    }

    // the rows of each layer, from BOTTOM up to BOTTOM + height - 1. The last element is the unchanged rows
    private static int[] countRows(Stack stack, int height, int[] offsets) {
        final int[] rows = new int[stack.counts().length + 1];
        final LayerBands bands = stack.bands(height);
        for (int y = BOTTOM; y < BOTTOM + height; y++) {
            final int layer = bands.layerAt(y, offsets);
            rows[(layer < 0) ? rows.length - 1 : layer]++;
        }
        return rows;
    }

    private static void checkCounts(Stack stack, int height) {
        final int[] rows = countRows(stack, height, new int[stack.edgeCount()]);
        final int spare = height - stack.fixedSum();
        for (int layer = 0; layer < stack.counts().length; layer++) {
            final int expected = (layer == stack.restIndex()) ? spare : stack.counts()[layer];
            assertEquals("rows of layer " + layer + ": " + stack + " height=" + height, expected, rows[layer]);
        }
        assertEquals(
            "unchanged rows: " + stack + " height=" + height,
            (stack.restIndex() < 0) ? spare : 0,
            rows[rows.length - 1]);
    }

    private static Stack generateStack(Random random) {
        final int[] counts = new int[1 + random.nextInt(5)];
        for (int layer = 0; layer < counts.length; layer++) counts[layer] = 1 + random.nextInt(8);
        final int restIndex = random.nextBoolean() ? random.nextInt(counts.length) : -1;
        if (restIndex >= 0) counts[restIndex] = 0;
        return new Stack(counts, restIndex);
    }

    private static final class Stack {

        private final int[] counts;
        private final int restIndex;
        private final LayerStack layers;

        Stack(int[] counts, int restIndex) {
            this.counts = counts;
            this.restIndex = restIndex;
            final List<LayerStack.Layer> stackLayers = new ArrayList<>();
            for (int layer = 0; layer < counts.length; layer++) {
                stackLayers.add(new LayerStack.Layer(new BaseBlock(1), counts[layer], layer == restIndex));
            }
            this.layers = new LayerStack(stackLayers);
        }

        int[] counts() {
            return counts;
        }

        int restIndex() {
            return restIndex;
        }

        LayerBands bands(int height) {
            return new LayerBands(layers, BOTTOM + height - 1, BOTTOM);
        }

        int edgeCount() {
            return bands(1).getEdgeCount();
        }

        int fixedSum() {
            int sum = 0;
            for (int layer = 0; layer < counts.length; layer++) {
                if (layer != restIndex) sum += counts[layer];
            }
            return sum;
        }

        @Override
        public String toString() {
            return "counts=" + Arrays.toString(counts) + " rest=" + restIndex;
        }
    }
}
