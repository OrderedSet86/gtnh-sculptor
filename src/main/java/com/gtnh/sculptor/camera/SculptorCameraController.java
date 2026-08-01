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

package com.gtnh.sculptor.camera;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Free camera and keyframed camera paths.
 *
 * <p>
 * Minecraft renders from {@code Minecraft.renderViewEntity}, so detaching the camera is a
 * matter of pointing that at a {@link SculptorCamera} and driving it ourselves. Mouse look is
 * left alone: the real player still turns with the mouse and the camera copies its orientation,
 * which gives ordinary look controls without touching the input pipeline.
 * </p>
 *
 * <p>
 * Movement is the part that needs care -- WASD would otherwise walk the real player off while
 * you fly around. Rather than intercept input with a mixin, the player's {@link MovementInput}
 * is swapped for a plain base instance whose {@code updatePlayerMoveState} does nothing, so the
 * player simply reads no input until the camera is put away.
 * </p>
 */
public class SculptorCameraController {

    private static final KeyBinding TOGGLE_KEY = new KeyBinding(
        "Sculptor: toggle free camera",
        Keyboard.KEY_C,
        "Sculptor");
    private static final KeyBinding ADD_KEYFRAME_KEY = new KeyBinding(
        "Sculptor: add camera keyframe",
        Keyboard.KEY_K,
        "Sculptor");
    private static final KeyBinding PLAY_KEY = new KeyBinding("Sculptor: play camera path", Keyboard.KEY_P, "Sculptor");
    // Not L: WorldEdit already binds that to its reference GUI, and a shared binding would
    // silently fire both.
    private static final KeyBinding CLEAR_KEY = new KeyBinding(
        "Sculptor: clear camera path",
        Keyboard.KEY_DELETE,
        "Sculptor");

    /** Blocks per tick at normal speed. Sprint multiplies it. */
    private static final double BASE_SPEED = 0.5D;
    private static final double SPRINT_MULTIPLIER = 4.0D;

    /** Ticks spent traversing each segment between keyframes. */
    private static final int TICKS_PER_SEGMENT = 40;

    private static final class Keyframe {

        final double x, y, z;
        final float yaw, pitch;

        Keyframe(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private final List<Keyframe> path = new ArrayList<Keyframe>();

    private SculptorCamera camera;
    private MovementInput savedInput;

    private boolean playing;
    private int playTick;

    public SculptorCameraController() {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(ADD_KEYFRAME_KEY);
        ClientRegistry.registerKeyBinding(PLAY_KEY);
        ClientRegistry.registerKeyBinding(CLEAR_KEY);
    }

    public boolean isActive() {
        return camera != null;
    }

    @SubscribeEvent
    public void onKey(KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (TOGGLE_KEY.isPressed()) {
            if (isActive()) {
                detach(mc);
            } else {
                attach(mc);
            }
        }

        if (CLEAR_KEY.isPressed()) {
            path.clear();
            playing = false;
            say(mc, "Sculptor: camera path cleared");
        }

        if (ADD_KEYFRAME_KEY.isPressed()) {
            addKeyframe(mc);
        }

        if (PLAY_KEY.isPressed()) {
            togglePlayback(mc);
        }
    }

    private void attach(Minecraft mc) {
        EntityClientPlayerMP player = mc.thePlayer;

        camera = new SculptorCamera(
            mc.theWorld,
            player.posX,
            player.posY + player.getEyeHeight(),
            player.posZ,
            player.rotationYaw,
            player.rotationPitch);

        mc.renderViewEntity = camera;

        // Freeze the real player: the base MovementInput reads nothing, so WASD reaches the
        // camera only.
        savedInput = player.movementInput;
        player.movementInput = new MovementInput();
        player.motionX = player.motionY = player.motionZ = 0.0D;

        say(mc, "Sculptor: free camera ON  [K] keyframe  [P] play  [Del] clear");
    }

    private void detach(Minecraft mc) {
        EntityClientPlayerMP player = mc.thePlayer;

        mc.renderViewEntity = player;
        if (savedInput != null) {
            player.movementInput = savedInput;
            savedInput = null;
        }
        camera = null;
        playing = false;

        say(mc, "Sculptor: free camera OFF");
    }

    private void addKeyframe(Minecraft mc) {
        if (!isActive()) {
            say(mc, "Sculptor: enable the free camera first");
            return;
        }
        path.add(new Keyframe(camera.posX, camera.posY, camera.posZ, camera.rotationYaw, camera.rotationPitch));
        say(mc, "Sculptor: keyframe " + path.size() + " added");
    }

    private void togglePlayback(Minecraft mc) {
        if (!isActive()) {
            say(mc, "Sculptor: enable the free camera first");
            return;
        }
        if (playing) {
            stopPlayback(mc, "Sculptor: playback stopped");
            return;
        }
        if (path.size() < 2) {
            say(mc, "Sculptor: need at least 2 keyframes");
            return;
        }
        playing = true;
        playTick = 0;
        say(mc, "Sculptor: playing " + path.size() + " keyframes");
    }

    /**
     * End playback, leaving the camera exactly where the path left it.
     *
     * <p>
     * Free movement takes its orientation from the real player, who never turned while the path
     * was playing. Without handing the camera's final angles back, the view would snap to
     * whatever direction the player happened to be facing the moment playback stopped -- which
     * reads as a jarring cut, usually toward terrain that has not been rendered yet.
     * </p>
     */
    private void stopPlayback(Minecraft mc, String message) {
        playing = false;
        if (camera != null && mc.thePlayer != null) {
            mc.thePlayer.rotationYaw = camera.rotationYaw;
            mc.thePlayer.rotationPitch = camera.rotationPitch;
            mc.thePlayer.prevRotationYaw = camera.rotationYaw;
            mc.thePlayer.prevRotationPitch = camera.rotationPitch;
        }
        say(mc, message);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isActive()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // The world can change underneath us (dimension change, disconnect); a camera holding a
        // stale world would render nothing useful.
        if (camera.worldObj != mc.theWorld) {
            detach(mc);
            return;
        }

        // Keep the player pinned while the camera is out, so physics does not drift it.
        mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0.0D;

        if (playing) {
            advancePlayback(mc);
        } else {
            applyFreeMovement(mc);
        }
    }

    private void applyFreeMovement(Minecraft mc) {
        // Mouse look still turns the real player, so the camera simply follows its orientation.
        float yaw = mc.thePlayer.rotationYaw;
        float pitch = mc.thePlayer.rotationPitch;

        double forward = 0.0D;
        double strafe = 0.0D;
        double vertical = 0.0D;

        if (mc.gameSettings.keyBindForward.getIsKeyPressed()) forward += 1.0D;
        if (mc.gameSettings.keyBindBack.getIsKeyPressed()) forward -= 1.0D;
        if (mc.gameSettings.keyBindLeft.getIsKeyPressed()) strafe += 1.0D;
        if (mc.gameSettings.keyBindRight.getIsKeyPressed()) strafe -= 1.0D;
        if (mc.gameSettings.keyBindJump.getIsKeyPressed()) vertical += 1.0D;
        if (mc.gameSettings.keyBindSneak.getIsKeyPressed()) vertical -= 1.0D;

        double speed = BASE_SPEED;
        if (mc.gameSettings.keyBindSprint.getIsKeyPressed()) {
            speed *= SPRINT_MULTIPLIER;
        }

        double x = camera.posX;
        double y = camera.posY;
        double z = camera.posZ;

        if (forward != 0.0D || strafe != 0.0D) {
            // Fly along the direction actually being looked at, including pitch, which is what
            // makes a free camera feel like a camera rather than a walking player.
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);

            double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
            double lookY = -Math.sin(pitchRad);
            double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

            double rightX = Math.cos(yawRad);
            double rightZ = Math.sin(yawRad);

            x += (lookX * forward + rightX * strafe) * speed;
            y += (lookY * forward) * speed;
            z += (lookZ * forward + rightZ * strafe) * speed;
        }

        y += vertical * speed;

        camera.moveTo(x, y, z, yaw, pitch);
    }

    private void advancePlayback(Minecraft mc) {
        int segments = path.size() - 1;
        int totalTicks = segments * TICKS_PER_SEGMENT;

        if (playTick >= totalTicks) {
            stopPlayback(mc, "Sculptor: playback finished");
            return;
        }

        int segment = playTick / TICKS_PER_SEGMENT;
        double t = (playTick % TICKS_PER_SEGMENT) / (double) TICKS_PER_SEGMENT;

        Keyframe p0 = path.get(Math.max(0, segment - 1));
        Keyframe p1 = path.get(segment);
        Keyframe p2 = path.get(Math.min(path.size() - 1, segment + 1));
        Keyframe p3 = path.get(Math.min(path.size() - 1, segment + 2));

        double x = catmullRom(p0.x, p1.x, p2.x, p3.x, t);
        double y = catmullRom(p0.y, p1.y, p2.y, p3.y, t);
        double z = catmullRom(p0.z, p1.z, p2.z, p3.z, t);

        // Angles are interpolated linearly along the shortest arc rather than with a spline:
        // overshoot in position reads as a smooth arc, but overshoot in yaw reads as a wobble.
        float yaw = p1.yaw + (float) (wrapDegrees(p2.yaw - p1.yaw) * t);
        float pitch = p1.pitch + (float) ((p2.pitch - p1.pitch) * t);

        if (playTick == 0) {
            // The jump from wherever the camera was parked to the first keyframe is a cut, not
            // a movement; interpolating it would smear across the gap for one frame.
            camera.setPositionAndRotationDirect(x, y, z, yaw, pitch);
        } else {
            camera.moveTo(x, y, z, yaw, pitch);
        }
        playTick++;
    }

    /**
     * Catmull-Rom spline through p1 and p2, shaped by their neighbours. Passes through every
     * keyframe, which a plain Bezier would not.
     */
    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5D * ((2.0D * p1) + (-p0 + p2) * t
            + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t2
            + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t3);
    }

    private static double wrapDegrees(double degrees) {
        return MathHelper.wrapAngleTo180_double(degrees);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!isActive() || event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);

        String line = playing ? String.format("Camera  playing  %d/%d", (playTick / TICKS_PER_SEGMENT) + 1, path.size())
            : String.format("Camera  free  %d keyframes  [K] add  [P] play  [Del] clear", path.size());

        mc.fontRenderer.drawStringWithShadow(line, 4, res.getScaledHeight() - 32, 0xFF66CCFF);
    }

    private static void say(Minecraft mc, String message) {
        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }

}
