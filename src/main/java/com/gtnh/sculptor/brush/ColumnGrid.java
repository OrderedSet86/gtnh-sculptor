package com.gtnh.sculptor.brush;

import java.util.Arrays;
import java.util.Deque;

/**
 * One Y value for each block column of a square around a brush centre, from {@code -radius} to {@code +radius} on X and
 * Z. {@link #NONE} marks a column with no value.
 *
 * <pre>
 *     radius 1:   index = (dz + 1) * 3 + (dx + 1)
 *     dz = -1:    0 1 2
 *     dz =  0:    3 4 5
 *     dz = +1:    6 7 8
 * </pre>
 */
public final class ColumnGrid {

    public static final int NONE = Integer.MIN_VALUE;

    private final int radius;
    private final int width;
    private final int[] values;

    public ColumnGrid(int radius) {
        this.radius = radius;
        this.width = 2 * radius + 1;
        this.values = new int[width * width];
        Arrays.fill(values, NONE);
    }

    public int getRadius() {
        return radius;
    }

    public int getWidth() {
        return width;
    }

    public int getCellCount() {
        return values.length;
    }

    public int getAt(int index) {
        return values[index];
    }

    public boolean hasValue(int dx, int dz) {
        return get(dx, dz) != NONE;
    }

    public void set(int dx, int dz, int value) {
        values[indexOf(dx, dz)] = value;
    }

    /**
     * Report whether a side neighbour of column ({@code dx}, {@code dz}) has a value of {@code lowest} or more. A
     * neighbour outside the grid has no value.
     */
    public boolean hasNeighbourAtOrAbove(int dx, int dz, int lowest) {
        for (int side = 0; side < 4; side++) {
            final int nx = dx + sideX(side);
            final int nz = dz + sideZ(side);
            if (!contains(nx, nz)) continue;

            final int neighbour = get(nx, nz);
            if (neighbour != NONE && neighbour >= lowest) return true;
        }
        return false;
    }

    /** Report whether a side neighbour of the cell at {@code index} is outside the grid or has no value. */
    public boolean isEdge(int index) {
        final int dx = index % width - radius;
        final int dz = index / width - radius;
        for (int side = 0; side < 4; side++) {
            final int nx = dx + sideX(side);
            final int nz = dz + sideZ(side);
            if (!contains(nx, nz) || get(nx, nz) == NONE) return true;
        }
        return false;
    }

    /**
     * Fill {@code distance} with the step count from the seed cells, by a breadth-first walk over the cells that have a
     * value. The caller sets each seed to 0 in {@code distance}, every other cell to {@link Integer#MAX_VALUE}, and
     * puts the seed indices in {@code frontier}. A cell that the walk does not reach keeps {@link Integer#MAX_VALUE}.
     */
    public void spreadDistances(Deque<Integer> frontier, int[] distance) {
        while (!frontier.isEmpty()) {
            final int index = frontier.poll();
            final int dx = index % width - radius;
            final int dz = index / width - radius;

            for (int side = 0; side < 4; side++) {
                final int nx = dx + sideX(side);
                final int nz = dz + sideZ(side);
                if (!contains(nx, nz)) continue;

                final int neighbour = indexOf(nx, nz);
                if (values[neighbour] == NONE) continue;
                if (distance[neighbour] <= distance[index] + 1) continue;

                distance[neighbour] = distance[index] + 1;
                frontier.add(neighbour);
            }
        }
    }

    public boolean contains(int dx, int dz) {
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }

    public int get(int dx, int dz) {
        return values[indexOf(dx, dz)];
    }

    public int indexOf(int dx, int dz) {
        return (dz + radius) * width + (dx + radius);
    }

    // sides 0 to 3: +X, -X, +Z, -Z
    private static int sideX(int side) {
        return (side == 0) ? 1 : (side == 1) ? -1 : 0;
    }

    private static int sideZ(int side) {
        return (side == 2) ? 1 : (side == 3) ? -1 : 0;
    }
}
