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

package com.gtnh.sculptor.brush;

import com.gtnh.sculptor.edit.PlacementMask;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;

/**
 * The brushes Sculptor offers, and the single place that says what each one does.
 *
 * <p>
 * Preview and commit both dispatch through {@link #apply}, so there is no way for the ghost to
 * disagree with the result: they are the same call against a context backed differently.
 * </p>
 */
public enum SculptorBrush {

    SPHERE("Sphere", null, 0, 0, false, true),
    HOLLOW_SPHERE("Hollow sphere", null, 0, 0, false, true),
    CYLINDER("Cylinder", "Height", 1, 32, false, true),
    HOLLOW_CYLINDER("Hollow cylinder", "Height", 1, 32, false, true),
    SMOOTH("Smooth", "Iterations", 1, 8, true, false),
    GRAVITY("Gravity", null, 0, 0, false, false),
    RAISE("Raise", null, 0, 0, true, true),
    LOWER("Lower", null, 0, 0, true, true),
    FLATTEN("Flatten", null, 0, 0, true, true),
    ERODE("Erode", null, 0, 0, true, false),
    MELT("Melt", null, 0, 0, true, false),
    TREE("Plant trees", null, 0, 0, true, false),
    REMOVE_FOLIAGE("Remove foliage", null, 0, 0, false, false);

    private final String label;
    private final String secondaryLabel;
    private final int secondaryMin;
    private final int secondaryMax;
    private final boolean usesStrength;

    /**
     * Whether the optional smoothing pass runs after this brush.
     *
     * <p>
     * True for anything that reshapes ground the height map can see. Not for smooth itself,
     * which would be circular, nor for gravity, erode, melt or foliage removal, whose whole
     * point is detail that smoothing would immediately undo.
     * </p>
     */
    private final boolean autoSmoothable;

    SculptorBrush(String label, String secondaryLabel, int secondaryMin, int secondaryMax, boolean usesStrength,
        boolean autoSmoothable) {
        this.label = label;
        this.secondaryLabel = secondaryLabel;
        this.secondaryMin = secondaryMin;
        this.secondaryMax = secondaryMax;
        this.usesStrength = usesStrength;
        this.autoSmoothable = autoSmoothable;
    }

    public String label() {
        return label;
    }

    public boolean hasSecondary() {
        return secondaryLabel != null;
    }

    public String secondaryLabel() {
        return secondaryLabel;
    }

    public int secondaryMin() {
        return secondaryMin;
    }

    public int secondaryMax() {
        return secondaryMax;
    }

    public boolean usesStrength() {
        return usesStrength;
    }

    public boolean isAutoSmoothable() {
        return autoSmoothable;
    }

    /**
     * Run this brush.
     *
     * @param ctx    preview or commit backing -- the brush does not know which
     * @param centre where the crosshair is pointing
     */
    public void apply(SculptorEditContext ctx, Vector centre, BrushParams params) throws MaxChangedBlocksException {
        ctx.setMaterial(params.material);

        // The auto-smooth pass has to be able to tell its own new material from the ground it
        // was dropped into, so a restrictive mask can stop it cutting into the latter. Only
        // worth the bookkeeping when that pass will actually run under such a mask.
        boolean guardAutoSmooth = autoSmoothable && params.autoSmooth > 0.0D
            && ctx.getPlacementMask() != PlacementMask.OVERWRITE;
        if (guardAutoSmooth) ctx.trackWrites();

        // Reaches well past the brush so the new shape is blended into the ground around it.
        // Smoothing only the footprint would round the thing off in isolation and leave the seam
        // where it meets untouched terrain, which is the opposite of the point.
        int reach = params.radius + Math.max(3, params.radius / 2);

        // Sampled before anything is placed, because afterwards there is no way to tell the
        // stroke's new material from the ground it is standing on -- and that distinction is what
        // lets the pass shave a shape back to ground level without also digging into the ground.
        // An int per column of the smoothing disc, so this costs a few thousand entries.
        int[] groundBefore = (autoSmoothable && params.autoSmooth > 0.0D)
            ? Brushes.sampleHeights(ctx, centre.getBlockX(), centre.getBlockY(), centre.getBlockZ(), reach)
            : null;

        switch (this) {
            case SPHERE:
                Brushes.sphere(ctx, centre, params, false);
                break;
            case HOLLOW_SPHERE:
                Brushes.sphere(ctx, centre, params, true);
                break;
            case CYLINDER:
                Brushes.cylinder(ctx, centre, params, false);
                break;
            case HOLLOW_CYLINDER:
                Brushes.cylinder(ctx, centre, params, true);
                break;
            case SMOOTH:
                Brushes.smooth(ctx, centre, params, params.secondary);
                break;
            case GRAVITY:
                Brushes.gravity(ctx, centre, params);
                break;
            case RAISE:
                Brushes.raiseLower(ctx, centre, params, 1);
                break;
            case LOWER:
                Brushes.raiseLower(ctx, centre, params, -1);
                break;
            case FLATTEN:
                Brushes.flatten(ctx, centre, params);
                break;
            case ERODE:
                Brushes.erode(ctx, centre, params, false);
                break;
            case MELT:
                Brushes.erode(ctx, centre, params, true);
                break;
            case TREE:
                Brushes.plantTrees(ctx, centre, params);
                break;
            case REMOVE_FOLIAGE:
                Brushes.removeFoliage(ctx, centre, params);
                break;
            default:
                break;
        }

        // Diffusion has to travel. A single pass shifts a column next to a two-block step by
        // well under half a block, which rounds away to nothing, so the blend only reaches past
        // the brush once enough passes have spread it -- more of them the stronger the setting.
        int passes = Math.max(2, (int) Math.round(params.autoSmooth * 6.0D));

        // Before smoothing, so the height map sees a shape that is standing on something rather
        // than one hanging in the air.
        boolean cantilever = fillsDownToGround() && params.autoSmooth > 0.0D
            && params.overhang == OverhangMode.CANTILEVER;

        int width = 2 * params.radius + 1;
        int[] shapeBottom = cantilever ? new int[width * width] : null;
        int[] shapeTop = cantilever ? new int[width * width] : null;
        if (cantilever) shapeProfile(centre, params, shapeBottom, shapeTop);

        if (fillsDownToGround() && params.autoSmooth > 0.0D) {
            if (cantilever) {
                // How much of a column's own thickness the underside may fall through. A cylinder
                // has a flat soffit to round off and can use the lot; a sphere is already curved
                // underneath, and letting it drop by its full thickness hangs a teardrop rather
                // than blending into anything, so it gets half.
                double depthFactor = (this == CYLINDER) ? 1.0D : 0.5D;
                Brushes
                    .cantileverOntoGround(ctx, centre, params, groundBefore, reach, shapeBottom, shapeTop, depthFactor);
            } else {
                Brushes.settleOntoGround(ctx, centre, params, groundBefore, reach);
            }
        }

        if (autoSmoothable && params.autoSmooth > 0.0D) {
            // Runs through the same context, so in commit mode it lands in the same undo entry
            // and in preview mode the ghost already shows the smoothed result.
            BrushParams smoothing = new BrushParams(
                reach,
                params.material,
                params.secondary,
                params.autoSmooth,
                0.0D,
                params.useSelectedMaterial);

            // The leash on reshaping terrain the player did not aim at: how far a column may be
            // raised, and how far below the ground it stood on it may be cut. Shaving the stroke's
            // own material back down is not bounded by this -- that is the taper, and a shape
            // standing proud of a slope has to be able to follow it all the way to the ground.
            int maxStep = Math.max(2, (int) Math.round(params.autoSmooth * params.radius / 3.0D));

            // "Air only" means leave what is already there alone, and the player did not ask for
            // this pass -- so here, unlike in a brush they chose, the mask governs cutting as
            // well as building. Undoing what the stroke just placed is still allowed.
            if (guardAutoSmooth) ctx.setGuardRemovals(true);
            try {
                // The cantilever keeps the shape it was drawn with -- its underside has already
                // been dealt with, and reworking the top as well is what turned a flat face into
                // an island of lumps. Filling to ground has no such face to preserve: there the
                // taper down to the surrounding terrain is the whole point.
                boolean keepPlacement = params.overhang == OverhangMode.CANTILEVER && fillsDownToGround();
                // Where the seam can plausibly be: no lower than the shape's own underside, less
                // its thickness again and the leash. Ground further down than that is not being
                // blended into anything, it is just in the same disc.
                int blendFloor = Integer.MIN_VALUE;
                if (keepPlacement && shapeBottom != null) {
                    int lowest = Integer.MAX_VALUE;
                    int thickest = 1;
                    for (int i = 0; i < shapeBottom.length; i++) {
                        if (shapeBottom[i] == Integer.MIN_VALUE) continue;
                        lowest = Math.min(lowest, shapeBottom[i]);
                        thickest = Math.max(thickest, shapeTop[i] - shapeBottom[i] + 1);
                    }
                    if (lowest != Integer.MAX_VALUE) blendFloor = lowest - thickest - maxStep;
                }

                Brushes.smooth(ctx, centre, smoothing, passes, maxStep, groundBefore, keepPlacement, blendFloor);
            } finally {
                if (guardAutoSmooth) ctx.setGuardRemovals(false);
            }
        }
    }

    /**
     * Where this brush's own material starts and ends in every column of its footprint, with
     * {@link Integer#MIN_VALUE} outside it.
     *
     * <p>
     * The cantilever used to work this out by looking at the world afterwards -- find the top of
     * the column, walk down to the bottom of the solid run. That reads correctly only where the
     * shape happens to hang in clear air. Placed into a cliff, under a ledge or down a crevice it
     * merges with what is already there, and the run is then the cliff's, not the shape's: the
     * pass either measured the wrong thickness or gave up on the column entirely, which is why a
     * cylinder cut into a rock face got no underside at all. The brush knows where it put its
     * blocks. Asking it removes the guesswork and every special case built around it.
     * </p>
     */
    private void shapeProfile(Vector centre, BrushParams params, int[] bottom, int[] top) {
        int r = params.radius;
        int width = 2 * r + 1;
        int cy = centre.getBlockY();
        double rSq = (r + 0.5D) * (r + 0.5D);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int index = (dz + r) * width + (dx + r);
                double dSq = dx * dx + (double) dz * dz;
                if (dSq > rSq) {
                    bottom[index] = Integer.MIN_VALUE;
                    continue;
                }

                if (this == CYLINDER) {
                    bottom[index] = cy;
                    top[index] = cy + Math.max(1, params.secondary) - 1;
                } else {
                    int half = (int) Math.floor(Math.sqrt(rSq - dSq));
                    bottom[index] = cy - half;
                    top[index] = cy + half;
                }
            }
        }
    }

    /**
     * Whether this shape should be extended down to the ground when auto-smooth is on.
     *
     * <p>
     * The solid shapes only. A hollow one filled at the base is no longer hollow. And it is tied
     * to auto-smooth deliberately: with the slider at zero a cylinder is a cylinder, base hanging
     * in the air and all, which is what you want when building rather than sculpting terrain.
     * </p>
     */
    private boolean fillsDownToGround() {
        return this == SPHERE || this == CYLINDER;
    }

    public SculptorBrush next() {
        SculptorBrush[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public SculptorBrush previous() {
        SculptorBrush[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    /** Resolve from a wire ordinal, defaulting rather than throwing on a bad value. */
    public static SculptorBrush fromOrdinal(int ordinal) {
        SculptorBrush[] all = values();
        return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : SPHERE;
    }

}
