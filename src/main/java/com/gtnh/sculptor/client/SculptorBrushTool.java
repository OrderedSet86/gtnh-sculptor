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
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.network.SculptorNetwork;
import com.gtnh.sculptor.render.GhostRenderer;
import com.sk89q.worldedit.Vector;
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

    /**
     * Key descriptions must not contain a colon.
     *
     * <p>
     * {@code GameSettings} persists bindings as {@code key_<description>:<code>} and parses
     * them back with {@code split(":")}, comparing only the first field. A description like
     * "Sculptor: commit brush" splits in the wrong place, matches nothing on load, and the
     * rebind is silently discarded and then overwritten on the next save. Lang keys are the
     * conventional form and have no colon.
     * </p>
     */
    private static final String CATEGORY = "key.categories.sculptor";

    private static final KeyBinding TOGGLE_KEY = new KeyBinding("key.sculptor.brush.toggle", Keyboard.KEY_B, CATEGORY);
    private static final KeyBinding COMMIT_KEY = new KeyBinding("key.sculptor.brush.commit", Keyboard.KEY_V, CATEGORY);
    private static final KeyBinding PANEL_KEY = new KeyBinding("key.sculptor.brush.panel", Keyboard.KEY_N, CATEGORY);

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

    /**
     * Ticks between repeats while the commit key is held.
     *
     * <p>
     * Holding to build up an effect -- repeated smoothing passes especially -- needs a rate
     * limit for two reasons. Committing every tick would flood the server with edits, and each
     * commit only becomes visible to the client once the server has applied it and sent the
     * chunk back, so firing faster than that round trip means later commits are computed from
     * terrain that does not reflect the earlier ones.
     * </p>
     */
    private static final int COMMIT_REPEAT_TICKS = 5;

    private boolean active;

    /** Counts down between repeated commits while the key is held. */
    private int commitCooldown;

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

        // Undo is //sc-undo, not a key. It is destructive and infrequent, and a key that
        // far-reaching is too easy to hit by accident mid-stroke.

        // Commit is driven from the tick instead of here, so that holding the key repeats.
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

        if (commitCooldown > 0) commitCooldown--;

        MovingObjectPosition hit = (mc.renderViewEntity != null) ? mc.renderViewEntity.rayTrace(REACH, 1.0F) : null;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            clearPreview();
            return;
        }

        // Held commit repeats on a timer. Checked before the preview refresh so a commit always
        // uses the preview the player can currently see.
        if (COMMIT_KEY.getIsKeyPressed() && commitCooldown == 0) {
            commit(mc);
            commitCooldown = COMMIT_REPEAT_TICKS;
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
            // Reads the client's copy of the world; captures rather than writing, so nothing
            // reaches the server until the player commits.
            SculptorEditContext ctx = SculptorEditContext.preview(ForgeWorldEdit.inst.getWorld(mc.theWorld));
            ctx.setPlacementMask(settings.getPlacementMask());

            settings.getBrush()
                .apply(ctx, new Vector(x, y, z), settings.toParams());

            previewedBlocks = ctx.getPendingChanges()
                .size();
            GhostRenderer.instance()
                .setPreview(ctx.getPendingChanges());

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
        // Silent rather than chatty: with a held key this would otherwise repeat every time the
        // crosshair crossed the sky.
        if (!hasPreview) return;
        BrushSettings settings = BrushSettings.instance();
        SculptorNetwork
            .sendCommit(settings.getBrush(), lastX, lastY, lastZ, settings.toParams(), settings.getPlacementMask());

        // Mark stale rather than clearing: the ghost stays on screen until the rebuild replaces
        // it, which stops a held commit from strobing the preview on and off.
        hasPreview = false;
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
