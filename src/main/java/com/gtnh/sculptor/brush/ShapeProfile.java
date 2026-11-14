package com.gtnh.sculptor.brush;

import com.github.bsideup.jabel.Desugar;

/**
 * The lowest and the highest Y of the material that a shape brush places, in each column of the footprint. A column
 * outside the footprint has {@link ColumnGrid#NONE} in {@code bottom}.
 */
@Desugar
public record ShapeProfile(ColumnGrid bottom, ColumnGrid top) {}
