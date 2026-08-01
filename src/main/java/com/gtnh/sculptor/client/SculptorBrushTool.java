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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.gtnh.sculptor.brush.BrushSettings;
import com.gtnh.sculptor.network.SculptorNetwork;
import com.gtnh.sculptor.render.GhostRenderer;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The interactive brush loop.
 *
 * <p>
 * While sculpt mode is on, the brush is previewed continuously at whatever the crosshair is
 * pointing at, the scroll wheel changes its radius, and V commits. The preview is rebuilt only
 * when the target block or the brush configuration actually changes, so dragging the crosshair
 * around costs one bake per block crossed rather than one per frame.
 * </p>
 *
 * <p>
 * Committing is deliberately not done here. The preview reads the client's copy of the world,
 * but the integrated server owns the blocks even in singleplayer, so a client-side write would
 * be reverted on the next chunk update. The commit is sent to the server, which replays the
 * same brush against a real {@link EditSession}. That is also exactly the shape multiplayer
 * needs, so there is no separate path to write later.
 * </p>
 */
public class SculptorBrushTool {

    private static final KeyBinding TOGGLE_KEY = new KeyBinding(
        "Sculptor: toggle sculpt mode",
        Keyboard.KEY_B,
        "Sculptor");
    private static final KeyBinding COMMIT_KEY = new KeyBinding("Sculptor: commit brush", Keyboard.KEY_V, "Sculptor");
    private static final KeyBinding PANEL_KEY = new KeyBinding(
        "Sculptor: open editor panel",
        Keyboard.KEY_N,
        "Sculptor");

    /**
     * How far the brush can reach, in blocks.
     *
     * <p>
     * Vanilla's {@code objectMouseOver} stops at the player's block reach (about 5 blocks),
     * which is useless for shaping terrain you are standing back from. This traces the same way
     * vanilla does -- {@code EntityRenderer.getMouseOver} is just
     * {@code renderViewEntity.rayTrace(reach, partialTicks)} -- only further, so targeting still
     * agrees with where the crosshair points. The cost of a trace is linear in distance, so this
     * is kept near a typical render distance rather than WorldEdit's 500.
     * </p>
     */
    private static final double REACH = 160.0D;

    private boolean active;

    /** What the last preview was built for, so we can tell when it is stale. */
    private int lastX, lastY, lastZ, lastRevision;
    private boolean hasPreview;

    private int previewedBlocks;
    private double targetDistance;

    public SculptorBrushTool() {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(COMMIT_KEY);
        ClientRegistry.registerKeyBinding(PANEL_KEY);
    }

    public boolean isActive() {
        return active;
    }

    @SubscribeEvent
    public void onKey(KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (TOGGLE_KEY.isPressed()) {
            active = !active;
            if (!active) {
                clearPreview();
            }
            say(mc, active ? "Sculptor: sculpt mode ON" : "Sculptor: sculpt mode OFF");
        }

        if (PANEL_KEY.isPressed()) {
            ClientGUI.open(new SculptorPanel());
        }

        if (active && COMMIT_KEY.isPressed()) {
            commit(mc);
        }
    }

    /**
     * Scroll changes the brush radius rather than the hotbar while sculpting.
     */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!active || event.dwheel == 0) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null) return;

        BrushSettings settings = BrushSettings.instance();
        settings.setRadius(settings.getRadius() + (event.dwheel > 0 ? 1 : -1));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (!active || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        MovingObjectPosition hit = (mc.renderViewEntity != null) ? mc.renderViewEntity.rayTrace(REACH, 1.0F) : null;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            clearPreview();
            return;
        }

        // Only rebuild when something actually changed -- this is what keeps a dragged
        // crosshair from re-tessellating every frame.
        int revision = BrushSettings.instance()
            .getRevision();
        if (hasPreview && hit.blockX == lastX
            && hit.blockY == lastY
            && hit.blockZ == lastZ
            && revision == lastRevision) {
            return;
        }

        updatePreview(mc, hit.blockX, hit.blockY, hit.blockZ, revision);
    }

    private void updatePreview(Minecraft mc, int x, int y, int z, int revision) {
        BrushSettings settings = BrushSettings.instance();
        try {
            EditSession preview = WorldEdit.getInstance()
                .getEditSessionFactory()
                .getPreviewEditSession(ForgeWorldEdit.inst.getWorld(mc.theWorld), -1);

            settings.getBrush()
                .apply(preview, new Vector(x, y, z), settings.getRadius(), settings.getMaterial());

            previewedBlocks = preview.getPendingChanges()
                .size();
            GhostRenderer.instance()
                .setPreview(preview.getPendingChanges());

            lastX = x;
            lastY = y;
            lastZ = z;
            lastRevision = revision;
            hasPreview = true;
            targetDistance = mc.thePlayer.getDistance(x + 0.5D, y + 0.5D, z + 0.5D);
        } catch (Exception e) {
            clearPreview();
            say(mc, "Sculptor: preview failed: " + e);
            e.printStackTrace();
        }
    }

    private void commit(Minecraft mc) {
        if (!hasPreview) {
            say(mc, "Sculptor: nothing to commit");
            return;
        }
        BrushSettings settings = BrushSettings.instance();
        SculptorNetwork.sendCommit(
            settings.getBrush(),
            lastX,
            lastY,
            lastZ,
            settings.getRadius(),
            settings.getMaterialId(),
            settings.getMaterialData());
        clearPreview();
    }

    private void clearPreview() {
        hasPreview = false;
        previewedBlocks = 0;
        GhostRenderer.instance()
            .clear();
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!active || event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        BrushSettings settings = BrushSettings.instance();
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);

        String line = hasPreview
            ? String.format(
                "Sculptor  %s  r=%d  %d blocks  %.0fm  [V] commit  [N] panel",
                settings.getBrush()
                    .label(),
                settings.getRadius(),
                previewedBlocks,
                targetDistance)
            : String.format(
                "Sculptor  %s  r=%d  no target  [N] panel",
                settings.getBrush()
                    .label(),
                settings.getRadius());

        mc.fontRenderer.drawStringWithShadow(line, 4, res.getScaledHeight() - 20, 0xFFFFAA00);
    }

    private static void say(Minecraft mc, String message) {
        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }

}
