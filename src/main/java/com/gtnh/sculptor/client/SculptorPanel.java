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

import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.gtnh.sculptor.brush.BrushSettings;
import com.gtnh.sculptor.brush.SculptorBrush;

/**
 * The editor panel: pick a brush, set its radius, choose the material.
 *
 * <p>
 * Everything here edits {@link BrushSettings}, which bumps a revision counter on change. The
 * brush loop watches that counter, so adjusting a value re-previews immediately -- there is no
 * apply button, and no way for the panel and the ghost to disagree.
 * </p>
 *
 * <p>
 * This is a modal screen. Axiom's equivalent is a docked panel that leaves the world
 * interactive, which ModularUI2 can do via its overlay/{@code HoloUI} support; that is a
 * worthwhile upgrade but a bigger piece of work than the panel itself, so the panel came first.
 * </p>
 */
public class SculptorPanel extends CustomModularScreen {

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 214;
    private static final int ROW_HEIGHT = 18;

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
        BrushSettings settings = BrushSettings.instance();

        int y = 6;

        panel.child(
            new TextWidget<>("Sculptor").scale(1.2f)
                .textAlign(Alignment.Center)
                .left(0)
                .right(0)
                .top(y)
                .height(ROW_HEIGHT));
        y += ROW_HEIGHT + 4;

        // --- Brush selection -------------------------------------------------------------
        panel.child(
            new TextWidget<>("Brush").textAlign(Alignment.CenterLeft)
                .left(8)
                .top(y)
                .height(ROW_HEIGHT));
        y += ROW_HEIGHT;

        panel.child(
            new ButtonWidget<>().overlay(com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> "<"))
                .size(16, ROW_HEIGHT)
                .left(8)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .previous());
                    return true;
                }));

        panel.child(
            new TextWidget<>(
                com.cleanroommc.modularui.api.drawable.IKey.dynamic(
                    () -> settings.getBrush()
                        .label())).textAlign(Alignment.Center)
                            .left(28)
                            .right(28)
                            .top(y)
                            .height(ROW_HEIGHT));

        panel.child(
            new ButtonWidget<>().overlay(com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> ">"))
                .size(16, ROW_HEIGHT)
                .right(8)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .next());
                    return true;
                }));
        y += ROW_HEIGHT + 6;

        // --- Radius ----------------------------------------------------------------------
        panel.child(
            new TextWidget<>(
                com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> "Radius: " + settings.getRadius()))
                    .textAlign(Alignment.CenterLeft)
                    .left(8)
                    .top(y)
                    .height(ROW_HEIGHT));
        y += ROW_HEIGHT;

        panel.child(
            new SliderWidget().bounds(BrushSettings.MIN_RADIUS, BrushSettings.MAX_RADIUS)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getRadius(),
                        value -> settings.setRadius((int) Math.round(value))))
                .size(PANEL_WIDTH - 16, ROW_HEIGHT)
                .left(8)
                .top(y));
        y += ROW_HEIGHT + 6;

        // --- Second parameter (cylinder height, smooth iterations, ...) ------------------
        panel.child(new TextWidget<>(com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> {
            SculptorBrush brush = settings.getBrush();
            return brush.hasSecondary() ? brush.secondaryLabel() + ": " + settings.getSecondary()
                : "(no second parameter)";
        })).textAlign(Alignment.CenterLeft)
            .left(8)
            .top(y)
            .height(ROW_HEIGHT));
        y += ROW_HEIGHT;

        panel.child(
            new SliderWidget().bounds(1, SECONDARY_SLIDER_MAX)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getSecondary(),
                        value -> settings.setSecondary((int) Math.round(value))))
                .size(PANEL_WIDTH - 16, ROW_HEIGHT)
                .left(8)
                .top(y));
        y += ROW_HEIGHT + 6;

        // --- Strength (terrain brushes) --------------------------------------------------
        panel.child(new TextWidget<>(com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> {
            SculptorBrush brush = settings.getBrush();
            return brush.usesStrength() ? String.format("Strength: %.0f%%", settings.getStrength() * 100.0D)
                : "(strength unused)";
        })).textAlign(Alignment.CenterLeft)
            .left(8)
            .top(y)
            .height(ROW_HEIGHT));
        y += ROW_HEIGHT;

        panel.child(
            new SliderWidget().bounds(0.05D, 1.0D)
                .value(new DoubleValue.Dynamic(() -> settings.getStrength(), settings::setStrength))
                .size(PANEL_WIDTH - 16, ROW_HEIGHT)
                .left(8)
                .top(y));
        y += ROW_HEIGHT + 6;

        // --- Material --------------------------------------------------------------------
        panel.child(
            new TextWidget<>(
                com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> "Material: " + materialName(settings)))
                    .textAlign(Alignment.CenterLeft)
                    .left(8)
                    .top(y)
                    .height(ROW_HEIGHT));
        y += ROW_HEIGHT;

        panel.child(
            new ButtonWidget<>().overlay(com.cleanroommc.modularui.api.drawable.IKey.dynamic(() -> "Use held block"))
                .size(PANEL_WIDTH - 16, ROW_HEIGHT)
                .left(8)
                .top(y)
                .onMousePressed(button -> {
                    takeHeldBlock(settings);
                    return true;
                }));

        return panel;
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
