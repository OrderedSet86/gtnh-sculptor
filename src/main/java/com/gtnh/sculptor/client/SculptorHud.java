package com.gtnh.sculptor.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import com.gtnh.sculptor.camera.SculptorCameraController;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The HUD lines of sculpt mode and of the free camera, in the top-left corner of the screen. The brush line is drawn
 * while sculpt mode is on, and the camera line while the free camera is on. The lines stack from the top with no gap.
 *
 * <pre>
 *     Sculptor  Sphere  r=9  4236 blocks  54m  [V] commit  [N] panel
 *     Camera  free  30 b/s  6 keyframes  [J/K/M] add  [P] play  [DELETE] clear
 * </pre>
 */
public class SculptorHud {

    private static final int LEFT = 4;
    private static final int TOP = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BRUSH_COLOR = 0xFFFFAA00;
    private static final int CAMERA_COLOR = 0xFF66CCFF;

    private final SculptorBrushTool brushTool;
    private final SculptorCameraController camera;

    public SculptorHud(SculptorBrushTool brushTool, SculptorCameraController camera) {
        this.brushTool = brushTool;
        this.camera = camera;
    }

    // the F3 debug lines are in the same corner
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || mc.gameSettings.showDebugInfo) return;

        int top = TOP;
        final String brushLine = brushTool.readHudLine();
        if (brushLine != null) {
            mc.fontRenderer.drawStringWithShadow(brushLine, LEFT, top, BRUSH_COLOR);
            top += LINE_HEIGHT;
        }
        final String cameraLine = camera.readHudLine();
        if (cameraLine != null) mc.fontRenderer.drawStringWithShadow(cameraLine, LEFT, top, CAMERA_COLOR);
    }
}
