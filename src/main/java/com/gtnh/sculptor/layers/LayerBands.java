package com.gtnh.sculptor.layers;

import java.util.List;

/**
 * The altitude bands of a {@link LayerStack} from {@code top} down to {@code bottom}. The layers above the rest layer
 * are counted down from {@code top}, and the layers below the rest layer up from {@code bottom}. The rest layer fills
 * the rows between, possibly none. With no rest layer, every layer is counted down from {@code top}, and the rows below
 * the last layer are unchanged.
 *
 * <p>
 * Where the counts add up to more rows than {@code top} to {@code bottom}, the upper layers win.
 * </p>
 */
public final class LayerBands {

    // the lowest row of each layer, with no spill
    private final int[] nominalEdges;
    private final int edgeCount;

    /** @param top the highest row of the bands, and {@code bottom} the lowest */
    public LayerBands(LayerStack stack, int top, int bottom) {
        final List<LayerStack.Layer> layers = stack.layers();
        int restIndex = -1;
        for (int layer = 0; layer < layers.size(); layer++) {
            if (layers.get(layer)
                .rest()) restIndex = layer;
        }
        edgeCount = (restIndex < 0) ? layers.size() : layers.size() - 1;

        final int[] countsBelow = new int[layers.size()];
        for (int layer = layers.size() - 2; layer >= 0; layer--) {
            countsBelow[layer] = countsBelow[layer + 1] + layers.get(layer + 1)
                .count();
        }

        // below the rest layer, a layer with no room under the layer above moves down past bottom, so the upper layers
        // win there too
        nominalEdges = new int[layers.size()];
        int edgeAbove = top + 1;
        for (int layer = 0; layer < layers.size(); layer++) {
            final int count = layers.get(layer)
                .count();
            final int edge;
            if (restIndex < 0 || layer < restIndex) {
                edge = edgeAbove - count;
            } else if (layer == restIndex) {
                edge = Math.min(bottom + countsBelow[layer], edgeAbove);
            } else {
                edge = Math.min(bottom + countsBelow[layer], edgeAbove - count);
            }
            nominalEdges[layer] = edge;
            edgeAbove = edge;
        }
    }

    /**
     * Find the layer of row {@code y}, from {@code bottom} to {@code top}.
     *
     * @param edgeOffsets the rows that each edge moves up, or down when negative, with {@link #getEdgeCount} elements.
     *                    An edge moves no higher than the edge of the layer above. Where two edges cross, the layer
     *                    between the two edges gets no rows
     * @return the index of the layer, or -1 where the row is unchanged
     */
    public int layerAt(int y, int[] edgeOffsets) {
        int edgeAbove = Integer.MAX_VALUE;
        for (int layer = 0; layer < nominalEdges.length; layer++) {
            int edge = nominalEdges[layer];
            if (layer < edgeCount) edge += edgeOffsets[layer];
            edge = Math.min(edge, edgeAbove);
            if (y >= edge) return layer;
            edgeAbove = edge;
        }
        return -1;
    }

    /**
     * Report the number of edges with an offset, the length of {@code edgeOffsets} in {@link #layerAt}. Edge {@code i}
     * is the lowest row of layer {@code i}. With a rest layer, the lowest row of the last layer has no offset.
     */
    public int getEdgeCount() {
        return edgeCount;
    }
}
