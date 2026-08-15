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
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The current brush configuration on the client.
 *
 * <p>
 * Edited by the editor panel and the scroll wheel, read by the preview, and serialised into the
 * commit packet. Kept in one place so the ghost, the HUD and the applied result cannot disagree
 * about what the brush is.
 * </p>
 */
public final class BrushSettings {

    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 20;

    private static final BrushSettings INSTANCE = new BrushSettings();

    private SculptorBrush brush = SculptorBrush.SPHERE;
    private int radius = 5;
    private int materialId = BlockID.STONE;
    private int materialData = 0;

    /** The brush's second parameter -- a cylinder's height, a smooth's iteration count. */
    private int secondary = 1;

    /** 0..1, how forcefully a terrain brush acts. */
    private double strength = 0.5D;

    /** What placement brushes are allowed to write over. */
    private PlacementMask placementMask = PlacementMask.OVERWRITE;

    /** 0..1 smoothing applied after a placement brush; zero disables it. */
    private double autoSmooth = 0.0D;

    /** Build with the chosen block rather than copying the existing surface material. */
    private boolean useSelectedMaterial = false;

    /** What auto-smooth does with the part of a shape that has nothing under it. */
    private OverhangMode overhang = OverhangMode.SETTLE;

    /** Bumped whenever anything changes, so the preview knows it is stale. */
    private int revision;

    private BrushSettings() {}

    public static BrushSettings instance() {
        return INSTANCE;
    }

    public OverhangMode getOverhang() {
        return overhang;
    }

    public void setOverhang(OverhangMode mode) {
        if (mode != null && mode != this.overhang) {
            this.overhang = mode;
            revision++;
        }
    }

    public SculptorBrush getBrush() {
        return brush;
    }

    public void setBrush(SculptorBrush brush) {
        if (brush != null && brush != this.brush) {
            this.brush = brush;
            // Each brush gives its second parameter a different meaning and range, so carrying
            // a cylinder's height of 32 into a smooth's iteration count would be nonsense.
            if (brush.hasSecondary()) {
                this.secondary = Math.max(brush.secondaryMin(), Math.min(brush.secondaryMax(), this.secondary));
            }
            revision++;
        }
    }

    public int getSecondary() {
        return secondary;
    }

    public void setSecondary(int value) {
        if (!brush.hasSecondary()) return;
        int clamped = Math.max(brush.secondaryMin(), Math.min(brush.secondaryMax(), value));
        if (clamped != this.secondary) {
            this.secondary = clamped;
            revision++;
        }
    }

    public double getStrength() {
        return strength;
    }

    public void setStrength(double value) {
        double clamped = Math.max(0.05D, Math.min(1.0D, value));
        if (clamped != this.strength) {
            this.strength = clamped;
            revision++;
        }
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        int clamped = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        if (clamped != this.radius) {
            this.radius = clamped;
            revision++;
        }
    }

    public int getMaterialId() {
        return materialId;
    }

    public int getMaterialData() {
        return materialData;
    }

    public void setMaterial(int id, int data) {
        if (id != this.materialId || data != this.materialData) {
            this.materialId = id;
            this.materialData = data;
            revision++;
        }
    }

    public BaseBlock getMaterial() {
        return new BaseBlock(materialId, materialData);
    }

    public PlacementMask getPlacementMask() {
        return placementMask;
    }

    public void setPlacementMask(PlacementMask mask) {
        if (mask != null && mask != this.placementMask) {
            this.placementMask = mask;
            revision++;
        }
    }

    public double getAutoSmooth() {
        return autoSmooth;
    }

    public void setAutoSmooth(double value) {
        double clamped = Math.max(0.0D, Math.min(1.0D, value));
        if (clamped != this.autoSmooth) {
            this.autoSmooth = clamped;
            revision++;
        }
    }

    public boolean isUseSelectedMaterial() {
        return useSelectedMaterial;
    }

    public void setUseSelectedMaterial(boolean value) {
        if (value != this.useSelectedMaterial) {
            this.useSelectedMaterial = value;
            revision++;
        }
    }

    /** Snapshot the current configuration for a brush run. */
    public BrushParams toParams() {
        return new BrushParams(radius, getMaterial(), secondary, strength, autoSmooth, useSelectedMaterial, overhang);
    }

    /**
     * A token that changes whenever the brush configuration does. Comparing it is how the
     * preview decides to rebuild without having to track each field.
     */
    public int getRevision() {
        return revision;
    }

}
