package com.gtnh.sculptor.brush;

import com.github.bsideup.jabel.Desugar;

/**
 * The limits of a smoothing pass on terrain that the player did not aim at.
 *
 * @param maxStep       how far a column may rise, and how far below {@code groundBefore} the pass may cut a column.
 *                      {@link Integer#MAX_VALUE} for no limit
 * @param groundBefore  the ground height of each column before the stroke, or {@code null} to take the current surface
 * @param keepPlacement skip each column that the stroke raised, and only raise the ground around those columns
 * @param blendFloor    with {@code keepPlacement}, skip a column whose surface is below this Y. {@link ColumnGrid#NONE}
 *                      for no floor
 */
@Desugar
public record SmoothingBounds(int maxStep, ColumnGrid groundBefore, boolean keepPlacement, int blendFloor) {

    /** No limits, for the smooth brush. The player aims the smooth brush at the terrain to reshape. */
    public static final SmoothingBounds UNBOUNDED = new SmoothingBounds(
        Integer.MAX_VALUE,
        null,
        false,
        ColumnGrid.NONE);
}
