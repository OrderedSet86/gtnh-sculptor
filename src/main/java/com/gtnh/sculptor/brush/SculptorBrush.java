package com.gtnh.sculptor.brush;

import java.util.Locale;

import com.gtnh.sculptor.edit.PlacementMask;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The brushes of Sculptor. The preview and the commit both run a brush through {@link #apply}, with a context of each
 * kind, so the ghost and the committed result come from the same call.
 */
public enum SculptorBrush {

    SPHERE(SecondaryParameter.NONE, false, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.sphere(false);
        }
    },
    HOLLOW_SPHERE(SecondaryParameter.NONE, false, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.sphere(true);
        }
    },
    CYLINDER(SecondaryParameter.HEIGHT, false, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.cylinder(false);
        }
    },
    HOLLOW_CYLINDER(SecondaryParameter.HEIGHT, false, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.cylinder(true);
        }
    },
    SMOOTH(SecondaryParameter.ITERATIONS, true, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.smooth(
                stroke.getParams()
                    .secondary(),
                SmoothingBounds.UNBOUNDED);
        }
    },
    GRAVITY(SecondaryParameter.NONE, false, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.gravity();
        }
    },
    RAISE(SecondaryParameter.NONE, true, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.raiseLower(1);
        }
    },
    LOWER(SecondaryParameter.NONE, true, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.raiseLower(-1);
        }
    },
    FLATTEN(SecondaryParameter.NONE, true, true) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.flatten();
        }
    },
    ERODE(SecondaryParameter.NONE, true, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.erode(false);
        }
    },
    MELT(SecondaryParameter.NONE, true, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.erode(true);
        }
    },
    TREE(SecondaryParameter.NONE, true, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.plantTrees();
        }
    },
    REMOVE_FOLIAGE(SecondaryParameter.NONE, false, false) {

        @Override
        public void place(BrushStroke stroke) throws MaxChangedBlocksException {
            stroke.removeFoliage();
        }
    };

    private final SecondaryParameter secondary;
    private final boolean usesStrength;

    // the auto-smooth pass runs after a brush that changes the height of the ground. Not after smooth, and not after
    // gravity, erode, melt, trees or foliage removal, whose detail the pass would undo
    private final boolean autoSmoothable;

    SculptorBrush(SecondaryParameter secondary, boolean usesStrength, boolean autoSmoothable) {
        this.secondary = secondary;
        this.usesStrength = usesStrength;
        this.autoSmoothable = autoSmoothable;
    }

    /**
     * Run the brush, then the auto-smooth pass when the brush is {@link #isAutoSmoothable auto-smoothable} and the
     * auto-smooth setting is above 0.
     *
     * @param ctx    a preview or a commit context. The brush runs the same code for both
     * @param centre the block at the crosshair
     */
    public void apply(SculptorEditContext ctx, Vector centre, BrushParams params) throws MaxChangedBlocksException {
        ctx.setReplaceBlock(params.replaceBlock());
        final BrushStroke stroke = new BrushStroke(ctx, centre, params);
        if (!(autoSmoothable && params.autoSmooth() > 0.0D)) {
            place(stroke);
            return;
        }

        // under a mask other than OVERWRITE, the pass may remove the material of this stroke only
        final boolean guardRemovals = ctx.getPlacementMask() != PlacementMask.OVERWRITE;
        if (guardRemovals) ctx.trackWrites();

        // the smoothing disc reaches past the brush, to blend the seam between the shape and the terrain around the
        // shape. Sample the ground before the placement. After the placement, the material of the stroke and the ground
        // are one solid run
        final int reach = params.radius() + Math.max(3, params.radius() / 2);
        final ColumnGrid groundBefore = stroke.sampleHeights(reach);

        place(stroke);

        // an air stroke carves, and leaves no shape to fill under or to curve down
        final boolean placesShape = fillsDownToGround() && params.material()
            .getType() != BlockID.AIR;
        final boolean cantilever = placesShape && params.overhang() == OverhangMode.CANTILEVER;
        final ShapeProfile shape = cantilever ? computeShapeProfile(centre, params) : null;
        if (cantilever) {
            // the fraction of the thickness of a column that the underside may drop. A cylinder has a flat underside to
            // round off. A sphere is round below already, and a drop of the full thickness of a column hangs a teardrop
            final double depthFactor = (this == CYLINDER) ? 1.0D : 0.5D;
            stroke.cantileverOntoGround(groundBefore, shape, depthFactor);
        } else if (placesShape) {
            stroke.settleOntoGround(groundBefore);
        }

        // the limit on a raise, and on a cut below the ground before the stroke. A cut of the material of the stroke is
        // the taper, with no limit
        final int maxStep = Math.max(2, (int) Math.round(params.autoSmooth() * params.radius() / 3.0D));

        // with a cantilever, the smoothing pass skips the columns of the shape. A pass over the top face moves each
        // column toward the terrain beside the column, and makes the flat face lumpy. With settle, the taper into the
        // terrain is the goal of the pass
        final SmoothingBounds bounds = new SmoothingBounds(
            maxStep,
            groundBefore,
            cantilever,
            cantilever ? computeBlendFloor(shape, maxStep) : ColumnGrid.NONE);

        // one pass moves a column beside a two-block step by well under half a block, which rounds to nothing. The
        // blend reaches past the brush only after several passes
        final int passes = Math.max(2, (int) Math.round(params.autoSmooth() * 6.0D));
        final BrushParams smoothing = params.toBuilder()
            .radius(reach)
            .strength(params.autoSmooth())
            .autoSmooth(0.0D)
            .build();

        if (guardRemovals) ctx.setGuardRemovals(true);
        try {
            new BrushStroke(ctx, centre, smoothing).smooth(passes, bounds);
        } finally {
            if (guardRemovals) ctx.setGuardRemovals(false);
        }
    }

    public abstract void place(BrushStroke stroke) throws MaxChangedBlocksException;

    /**
     * Report whether the shape fills down to the ground, or curves the underside down, when auto-smooth is on: true for
     * the solid shapes. Filled at the base, a hollow shape is not hollow. With auto-smooth at 0, no shape changes, for
     * building in the air.
     */
    private boolean fillsDownToGround() {
        return this == SPHERE || this == CYLINDER;
    }

    /**
     * Compute where the material of this brush starts and ends in each column of the footprint, from the brush and not
     * from the world.
     *
     * <p>
     * In the world, a shape placed into a cliff, under a ledge or down a crevice joins the terrain, and the solid run
     * of a column is then the run of the terrain. With a run read from the world, the thickness would be wrong, or a
     * cylinder cut into a rock face would get no underside.
     * </p>
     */
    private ShapeProfile computeShapeProfile(Vector centre, BrushParams params) {
        final int r = params.radius();
        final int cy = centre.getBlockY();
        final double rSq = (r + 0.5D) * (r + 0.5D);
        final ColumnGrid bottom = new ColumnGrid(r);
        final ColumnGrid top = new ColumnGrid(r);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double dSq = dx * dx + (double) dz * dz;
                if (dSq > rSq) continue;

                if (this == CYLINDER) {
                    bottom.set(dx, dz, cy);
                    top.set(dx, dz, cy + Math.max(1, params.secondary()) - 1);
                } else {
                    final int half = (int) Math.floor(Math.sqrt(rSq - dSq));
                    bottom.set(dx, dz, cy - half);
                    top.set(dx, dz, cy + half);
                }
            }
        }
        return new ShapeProfile(bottom, top);
    }

    // the lowest Y of a seam: the lowest bottom of the shape, less the greatest thickness of the shape and less
    // maxStep. Ground further down is in the disc but is not a seam
    private static int computeBlendFloor(ShapeProfile shape, int maxStep) {
        final ColumnGrid bottoms = shape.bottom();
        final ColumnGrid tops = shape.top();
        int lowest = Integer.MAX_VALUE;
        int thickest = 1;
        for (int index = 0; index < bottoms.getCellCount(); index++) {
            final int bottom = bottoms.getAt(index);
            if (bottom == ColumnGrid.NONE) continue;
            lowest = Math.min(lowest, bottom);
            thickest = Math.max(thickest, tops.getAt(index) - bottom + 1);
        }
        return (lowest == Integer.MAX_VALUE) ? ColumnGrid.NONE : lowest - thickest - maxStep;
    }

    public String getLangKey() {
        return "sculptor.brush." + name().toLowerCase(Locale.ROOT);
    }

    public boolean hasSecondary() {
        return secondary != SecondaryParameter.NONE;
    }

    /**
     * The lang key of the second parameter, such as the height of a cylinder, or {@code null} when
     * {@link #hasSecondary} is false.
     */
    public String getSecondaryLangKey() {
        return secondary.langKey;
    }

    public int getSecondaryMin() {
        return secondary.min;
    }

    public int getSecondaryMax() {
        return secondary.max;
    }

    public boolean usesStrength() {
        return usesStrength;
    }

    public boolean isAutoSmoothable() {
        return autoSmoothable;
    }

    /** The meaning and the range of the second parameter of a brush. */
    private enum SecondaryParameter {

        NONE(null, 0, 0),
        HEIGHT("sculptor.brush.secondary.height", 1, 32),
        ITERATIONS("sculptor.brush.secondary.iterations", 1, 8);

        private final String langKey;
        private final int min;
        private final int max;

        SecondaryParameter(String langKey, int min, int max) {
            this.langKey = langKey;
            this.min = min;
            this.max = max;
        }
    }
}
