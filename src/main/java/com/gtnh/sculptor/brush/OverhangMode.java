package com.gtnh.sculptor.brush;

import java.util.Locale;

/**
 * The treatment of the part of a placed solid shape with nothing under the shape, when auto-smooth is on.
 *
 * <p>
 * A solid shape fills up from the target height across the footprint, so on a slope the downhill part hangs in the air.
 * The smoothing pass is a height map, with no data for the underside of a shape.
 * </p>
 */
public enum OverhangMode {

    /** Fill down from the shape to the terrain, so the overhang becomes a solid mass on the hillside. */
    SETTLE,

    /**
     * Leave the overhang hanging, and curve the underside of the overhang down into the ground where the shape is
     * anchored. The fill adds the most material at the slope, and less toward the free edge.
     */
    CANTILEVER;

    public String getLangKey() {
        return "sculptor.overhang." + name().toLowerCase(Locale.ROOT);
    }

    public OverhangMode next() {
        final OverhangMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
