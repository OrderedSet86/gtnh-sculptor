package com.gtnh.sculptor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.brush.BrushSettings;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.network.CommitRequest;
import com.gtnh.sculptor.network.SculptorNetwork;
import com.gtnh.sculptor.render.GhostRenderer;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Sculpt mode on the client: the brush preview at the crosshair, the radius on the scroll wheel, and the commit key.
 *
 * <p>
 * The preview is rebuilt only when the target block or the {@link BrushSettings#getRevision revision} of the settings
 * changes. On a commit, the brush and the settings go to the server, and the server runs the brush through an
 * {@code EditSession}. The blocks are server state, also in singleplayer, so the server reverts a write on the client.
 * </p>
 */
public class SculptorBrushTool {

    // `GameSettings` saves a binding as key_<description>:<code> and splits the line on ':'. A description with a colon
    // never matches on load, so use lang keys
    private static final String CATEGORY = "key.categories.sculptor";
    private static final KeyBinding TOGGLE_KEY = new KeyBinding("key.sculptor.brush.toggle", Keyboard.KEY_B, CATEGORY);
    private static final KeyBinding COMMIT_KEY = new KeyBinding("key.sculptor.brush.commit", Keyboard.KEY_V, CATEGORY);
    private static final KeyBinding PANEL_KEY = new KeyBinding("key.sculptor.brush.panel", Keyboard.KEY_N, CATEGORY);

    // the trace of vanilla `objectMouseOver` stops at the block reach of the player, about 5 blocks. onClientTick()
    // calls the same `rayTrace()` with this reach. The cost of a trace grows with the reach
    private static final double REACH = 160.0D;

    // the client gets the result of a commit with the next chunk update from the server. A commit faster than that
    // round trip is computed from terrain without the earlier commits, and fast commits flood the server
    private static final int COMMIT_REPEAT_TICKS = 5;

    private boolean active;
    private int commitCooldown;

    // the target and settings revision of the current preview
    private int lastX, lastY, lastZ, lastRevision;
    private boolean hasPreview;

    private int previewedBlocks;
    private double targetDistance;

    private String hudLine = "";
    private boolean hudHasPreview;
    private SculptorBrush hudBrush;
    private int hudRadius = -1;
    private int hudBlocks = -1;
    private long hudDistance = -1;

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
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (TOGGLE_KEY.isPressed()) {
            active = !active;
            if (!active) {
                clearPreview();
            }
            SculptorChat.sendTranslated(mc.thePlayer, active ? "sculptor.brush.mode_on" : "sculptor.brush.mode_off");
        }

        if (PANEL_KEY.isPressed()) {
            ClientGUI.open(new SculptorPanel());
        }

        // onClientTick() reads the commit key, so the commit repeats while the player keeps the key down
    }

    /** While sculpting, the scroll wheel changes the brush radius and not the hotbar slot. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (!active || event.dwheel == 0) return;

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null) return;

        final BrushSettings settings = BrushSettings.instance();
        settings.setRadius(settings.getRadius() + (event.dwheel > 0 ? 1 : -1));
        event.setCanceled(true);
    }

    /**
     * Trace the crosshair up to {@link #REACH} blocks, commit while the commit key is down, with
     * {@link #COMMIT_REPEAT_TICKS} ticks between commits, and rebuild the preview when the target or the settings
     * change.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        final Minecraft mc = Minecraft.getMinecraft();
        if (!active || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (commitCooldown > 0) commitCooldown--;

        final MovingObjectPosition hit = (mc.renderViewEntity != null) ? mc.renderViewEntity.rayTrace(REACH, 1.0F)
            : null;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            clearPreview();
            return;
        }

        // commit before the preview is rebuilt, so the commit uses the target of the preview on screen
        if (COMMIT_KEY.getIsKeyPressed() && commitCooldown == 0) {
            commit();
            commitCooldown = COMMIT_REPEAT_TICKS;
        }

        final int revision = BrushSettings.instance()
            .getRevision();
        if (hasPreview && hit.blockX == lastX
            && hit.blockY == lastY
            && hit.blockZ == lastZ
            && revision == lastRevision) {
            return;
        }

        updatePreview(mc, hit, revision);
    }

    /** Run the brush on a preview context at the hit block, and give the changes to the {@link GhostRenderer}. */
    private void updatePreview(Minecraft mc, MovingObjectPosition hit, int revision) {
        final BrushSettings settings = BrushSettings.instance();
        try {
            final SculptorEditContext ctx = SculptorEditContext.preview(ForgeWorldEdit.inst.getWorld(mc.theWorld));
            ctx.setPlacementMask(settings.getPlacementMask());

            settings.getBrush()
                .apply(ctx, new Vector(hit.blockX, hit.blockY, hit.blockZ), settings.toParams());

            previewedBlocks = ctx.getPendingChanges()
                .size();
            GhostRenderer.instance()
                .setPreview(ctx.getPendingChanges());

            lastX = hit.blockX;
            lastY = hit.blockY;
            lastZ = hit.blockZ;
            lastRevision = revision;
            hasPreview = true;
            targetDistance = mc.thePlayer.getDistance(hit.blockX + 0.5D, hit.blockY + 0.5D, hit.blockZ + 0.5D);
        } catch (MaxChangedBlocksException | RuntimeException exception) { // noqa: NAR142, the brush runs WorldEdit
            // and tree generator code of other mods. FML rethrows an exception from a tick handler, which crashes the
            // client
            clearPreview();
            SculptorMod.LOG.warn(
                "preview.failed brush={} x={} y={} z={}",
                settings.getBrush(),
                hit.blockX,
                hit.blockY,
                hit.blockZ,
                exception);
            SculptorChat.sendTranslated(mc.thePlayer, "sculptor.brush.preview_failed", exception.toString());
        }
    }

    private void commit() {
        // no message without a preview. With the key down, a message would go to chat each time the crosshair crosses
        // the sky
        if (!hasPreview) return;
        final BrushSettings settings = BrushSettings.instance();
        SculptorNetwork.sendCommit(
            new CommitRequest(
                settings.getBrush(),
                new Vector(lastX, lastY, lastZ),
                settings.toParams(),
                settings.getPlacementMask()));

        // keep the ghost on screen until the rebuild, so the ghost does not flicker while the commit key is down
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

        final int hudTop = 20;
        final int hudColor = 0xFFFFAA00;
        updateHudLine();
        Minecraft.getMinecraft().fontRenderer
            .drawStringWithShadow(hudLine, 4, event.resolution.getScaledHeight() - hudTop, hudColor);
    }

    /** Rebuild the HUD text when a value in the text changes. The overlay event fires each frame. */
    private void updateHudLine() {
        final BrushSettings settings = BrushSettings.instance();
        final SculptorBrush brush = settings.getBrush();
        final int radius = settings.getRadius();
        final long distance = Math.round(targetDistance);
        if (!(hasPreview != hudHasPreview || brush != hudBrush
            || radius != hudRadius
            || previewedBlocks != hudBlocks
            || distance != hudDistance)) {
            return;
        }

        hudHasPreview = hasPreview;
        hudBrush = brush;
        hudRadius = radius;
        hudBlocks = previewedBlocks;
        hudDistance = distance;
        final String brushName = StatCollector.translateToLocal(brush.getLangKey());
        final String panelKey = GameSettings.getKeyDisplayString(PANEL_KEY.getKeyCode());
        hudLine = hasPreview
            ? StatCollector.translateToLocalFormatted(
                "sculptor.brush.hud.target",
                brushName,
                radius,
                previewedBlocks,
                distance,
                GameSettings.getKeyDisplayString(COMMIT_KEY.getKeyCode()),
                panelKey)
            : StatCollector.translateToLocalFormatted("sculptor.brush.hud.no_target", brushName, radius, panelKey);
    }

}
