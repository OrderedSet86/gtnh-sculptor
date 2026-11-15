package com.gtnh.sculptor.brush;

import com.gtnh.sculptor.edit.PlacementMask;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The brush settings on the client. The panel and the scroll wheel write the settings. The preview and the commit
 * packet get the settings from {@link #toParams}, so the ghost, the HUD and the commit use one configuration.
 */
public final class BrushSettings {

    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 20;

    private static final double MIN_STRENGTH = 0.05D;
    private static final BrushSettings INSTANCE = new BrushSettings();

    private SculptorBrush brush = SculptorBrush.SPHERE;
    private int radius = 5;
    private int materialId = BlockID.STONE;
    private int materialData;
    private int secondary = 1;
    private double strength = 0.5D;
    private PlacementMask placementMask = PlacementMask.OVERWRITE;
    private double autoSmooth;
    private boolean useSelectedMaterial;
    private OverhangMode overhang = OverhangMode.SETTLE;
    private int replaceId = BlockID.DIRT;
    private int replaceData;

    // incremented on each change of a setting
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
            // the range of the second parameter depends on the brush, such as a cylinder height of 32 and at most 8
            // iterations of smooth
            if (brush.hasSecondary()) {
                this.secondary = Math.max(brush.getSecondaryMin(), Math.min(brush.getSecondaryMax(), this.secondary));
            }
            revision++;
        }
    }

    public int getSecondary() {
        return secondary;
    }

    public void setSecondary(int value) {
        if (!brush.hasSecondary()) return;
        final int clamped = Math.max(brush.getSecondaryMin(), Math.min(brush.getSecondaryMax(), value));
        if (clamped != this.secondary) {
            this.secondary = clamped;
            revision++;
        }
    }

    public double getStrength() {
        return strength;
    }

    public void setStrength(double value) {
        final double clamped = Math.max(MIN_STRENGTH, Math.min(1.0D, value));
        if (clamped != this.strength) {
            this.strength = clamped;
            revision++;
        }
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        final int clamped = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
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

    public int getReplaceId() {
        return replaceId;
    }

    public int getReplaceData() {
        return replaceData;
    }

    public void setReplaceBlock(int id, int data) {
        if (id != this.replaceId || data != this.replaceData) {
            this.replaceId = id;
            this.replaceData = data;
            revision++;
        }
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
        final double clamped = Math.max(0.0D, Math.min(1.0D, value));
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

    public BrushParams toParams() {
        return BrushParams.builder()
            .radius(radius)
            .material(getMaterial())
            .secondary(secondary)
            .strength(strength)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(useSelectedMaterial)
            .overhang(overhang)
            .replaceBlock(new BaseBlock(replaceId, replaceData))
            .build();
    }

    public BaseBlock getMaterial() {
        return new BaseBlock(materialId, materialData);
    }

    /** A number that changes on each change of a setting, so {@code SculptorBrushTool} compares one value. */
    public int getRevision() {
        return revision;
    }

}
