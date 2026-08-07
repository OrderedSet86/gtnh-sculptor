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

package com.gtnh.sculptor.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnh.sculptor.brush.BrushSettings;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.gtnh.sculptor.camera.CameraSettings;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * The editor panel: brush settings on the left, camera speeds on the right.
 *
 * <p>
 * Everything here edits {@link BrushSettings} or {@link CameraSettings} directly. Brush changes
 * bump a revision counter that the brush loop watches, so adjusting a value re-previews
 * immediately -- there is no apply button, and no way for the panel and the ghost to disagree.
 * </p>
 *
 * <p>
 * This is a modal screen. Axiom's equivalent is a docked panel that leaves the world
 * interactive, which ModularUI2 can do via its overlay/{@code HoloUI} support; that is a
 * worthwhile upgrade but a bigger piece of work than the panel itself, so the panel came first.
 * </p>
 */
public class SculptorPanel extends CustomModularScreen {

    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 268;
    private static final int ROW_HEIGHT = 18;

    private static final int LEFT_X = 8;
    private static final int RIGHT_X = 168;
    private static final int COLUMN_WIDTH = 144;

    /**
     * The second parameter means something different per brush and has a different range, but a
     * slider's bounds are fixed when it is built. Rather than rebuild the panel on every brush
     * change, the slider spans the widest range any brush uses and {@code BrushSettings} clamps
     * to whatever the current brush actually allows -- so dragging past a brush's maximum pins
     * there, and the label always shows the value in effect.
     */
    private static final int SECONDARY_SLIDER_MAX = 32;

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        ModularPanel panel = ModularPanel.defaultPanel("sculptor", PANEL_WIDTH, PANEL_HEIGHT);
        BrushSettings brush = BrushSettings.instance();
        CameraSettings camera = CameraSettings.instance();

        panel.child(
            new TextWidget<>("Sculptor").scale(1.2f)
                .textAlign(Alignment.Center)
                .left(0)
                .right(0)
                .top(6)
                .height(ROW_HEIGHT));

        buildBrushColumn(panel, brush);
        buildCameraColumn(panel, camera);

        return panel;
    }

    private void buildBrushColumn(ModularPanel panel, BrushSettings settings) {
        int y = 28;

        panel.child(label("Brush", LEFT_X, y));
        y += ROW_HEIGHT;

        panel.child(
            new ButtonWidget<>().overlay(IKey.str("<"))
                .size(16, ROW_HEIGHT)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .previous());
                    return true;
                }));

        panel.child(
            new TextWidget<>(
                IKey.dynamic(
                    () -> settings.getBrush()
                        .label())).textAlign(Alignment.Center)
                            .left(LEFT_X + 20)
                            .width(COLUMN_WIDTH - 40)
                            .top(y)
                            .height(ROW_HEIGHT));

        panel.child(
            new ButtonWidget<>().overlay(IKey.str(">"))
                .size(16, ROW_HEIGHT)
                .left(LEFT_X + COLUMN_WIDTH - 16)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .next());
                    return true;
                }));
        y += ROW_HEIGHT + 4;

        panel.child(dynamicLabel(() -> "Radius: " + settings.getRadius(), LEFT_X, y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(BrushSettings.MIN_RADIUS, BrushSettings.MAX_RADIUS)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getRadius(),
                        value -> settings.setRadius((int) Math.round(value))))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel.child(dynamicLabel(() -> {
            SculptorBrush b = settings.getBrush();
            return b.hasSecondary() ? b.secondaryLabel() + ": " + settings.getSecondary() : "(no second parameter)";
        }, LEFT_X, y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(1, SECONDARY_SLIDER_MAX)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getSecondary(),
                        value -> settings.setSecondary((int) Math.round(value))))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel.child(dynamicLabel(() -> {
            SculptorBrush b = settings.getBrush();
            return b.usesStrength() ? String.format("Strength: %.0f%%", settings.getStrength() * 100.0D)
                : "(strength unused)";
        }, LEFT_X, y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(0.05D, 1.0D)
                .value(new DoubleValue.Dynamic(settings::getStrength, settings::setStrength))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel.child(dynamicLabel(() -> "Material: " + materialName(settings), LEFT_X, y));
        y += 12;
        panel.child(
            new ButtonWidget<>().overlay(IKey.str("Use held block"))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    takeHeldBlock(settings);
                    return true;
                }));
        y += 22;

        // --- What placement brushes may write over -------------------------------------
        panel.child(
            new ButtonWidget<>().overlay(
                IKey.dynamic(
                    () -> settings.getPlacementMask()
                        .label()))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setPlacementMask(
                        settings.getPlacementMask()
                            .next());
                    return true;
                }));
        y += 22;

        // --- Build with the selected block rather than the existing surface -------------
        panel.child(
            new ButtonWidget<>().overlay(
                IKey.dynamic(
                    () -> settings.isUseSelectedMaterial() ? "Material: selected block" : "Material: keep existing"))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setUseSelectedMaterial(!settings.isUseSelectedMaterial());
                    return true;
                }));
        y += 22;

        // --- Smoothing applied after a placement brush ---------------------------------
        panel.child(
            dynamicLabel(
                () -> (settings.getAutoSmooth() <= 0.0D) ? "Auto-smooth: off"
                    : String.format("Auto-smooth: %.0f%%", settings.getAutoSmooth() * 100.0D),
                LEFT_X,
                y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(0.0D, 1.0D)
                .value(new DoubleValue.Dynamic(settings::getAutoSmooth, settings::setAutoSmooth))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
    }

    private void buildCameraColumn(ModularPanel panel, CameraSettings settings) {
        int y = 28;

        panel.child(label("Camera (blocks/sec)", RIGHT_X, y));
        y += ROW_HEIGHT + 2;

        y = speedField(panel, "Fly", y, settings::getFlySpeed, settings::setFlySpeed);

        panel.child(label("Keyframe approach speeds", RIGHT_X, y));
        y += 14;

        for (final PathSpeed tier : PathSpeed.values()) {
            y = speedField(
                panel,
                tier.label(),
                y,
                () -> settings.getPathSpeed(tier),
                value -> settings.setPathSpeed(tier, value));
        }

        panel.child(
            new TextWidget<>(IKey.str("A segment takes as long as its\nlength needs at its speed.")).scale(0.8f)
                .textAlign(Alignment.CenterLeft)
                .left(RIGHT_X)
                .width(COLUMN_WIDTH)
                .top(y + 4)
                .height(24));
    }

    /**
     * A labelled numeric entry. Decimals are allowed because a slow drift is often well under
     * one block per second, where whole numbers are too coarse to be useful.
     */
    private int speedField(ModularPanel panel, String name, int y, java.util.function.DoubleSupplier getter,
        java.util.function.DoubleConsumer setter) {
        panel.child(
            new TextWidget<>(IKey.str(name)).textAlign(Alignment.CenterLeft)
                .left(RIGHT_X)
                .width(50)
                .top(y)
                .height(16));

        panel.child(
            new TextFieldWidget().value(new DoubleValue.Dynamic(getter, setter))
                .numbersDouble(0.1D, 1000.0D)
                .size(COLUMN_WIDTH - 54, 16)
                .left(RIGHT_X + 54)
                .top(y));

        return y + 20;
    }

    private static TextWidget<?> label(String text, int x, int y) {
        return new TextWidget<>(IKey.str(text)).textAlign(Alignment.CenterLeft)
            .left(x)
            .width(COLUMN_WIDTH)
            .top(y)
            .height(14);
    }

    private static TextWidget<?> dynamicLabel(java.util.function.Supplier<String> text, int x, int y) {
        return new TextWidget<>(IKey.dynamic(text::get)).textAlign(Alignment.CenterLeft)
            .left(x)
            .width(COLUMN_WIDTH)
            .top(y)
            .height(12);
    }

    /**
     * Picking the material off the hotbar avoids needing a block picker widget, and matches how
     * people already choose what to build with.
     */
    private static void takeHeldBlock(BrushSettings settings) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return;

        Block block = Block.getBlockFromItem(held.getItem());
        if (block == null || block == net.minecraft.init.Blocks.air) return;

        settings.setMaterial(Block.getIdFromBlock(block), held.getItemDamage() & 0xF);
    }

    private static String materialName(BrushSettings settings) {
        Block block = Block.getBlockById(settings.getMaterialId());
        if (block == null) return "unknown";
        String name = block.getLocalizedName();
        return (settings.getMaterialData() != 0) ? name + ":" + settings.getMaterialData() : name;
    }

}
