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
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Keyboard;

import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

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

    /**
     * Key descriptions must not contain a colon.
     *
     * <p>
     * {@code GameSettings} persists bindings as {@code key_<description>:<code>} and parses them
     * back with {@code split(":")}, comparing only the first field. A description like
     * "Sculptor: play camera path" splits in the wrong place, matches nothing on load, and the
     * rebind is silently discarded and then overwritten on the next save.
     * </p>
     */
    private static final String CATEGORY = "key.categories.sculptor";

    private static final KeyBinding TOGGLE_KEY = new KeyBinding("key.sculptor.camera.toggle", Keyboard.KEY_C, CATEGORY);
    private static final KeyBinding ADD_SLOW_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.slow",
        Keyboard.KEY_J,
        CATEGORY);
    private static final KeyBinding ADD_NORMAL_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.normal",
        Keyboard.KEY_K,
        CATEGORY);
    private static final KeyBinding ADD_FAST_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.fast",
        Keyboard.KEY_M,
        CATEGORY);
    private static final KeyBinding PLAY_KEY = new KeyBinding("key.sculptor.camera.play", Keyboard.KEY_P, CATEGORY);
    private static final KeyBinding CLEAR_KEY = new KeyBinding(
        "key.sculptor.camera.clear",
        Keyboard.KEY_DELETE,
        CATEGORY);

    private static final int TICKS_PER_SECOND = 20;

    private static final class Keyframe {

        final double x, y, z;
        final float yaw, pitch;
        /** How fast to travel <em>into</em> this keyframe from the one before it. */
        final PathSpeed speed;

        Keyframe(double x, double y, double z, float yaw, float pitch, PathSpeed speed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.speed = speed;
        }
    }

    private final List<Keyframe> path = new ArrayList<Keyframe>();

    private SculptorCamera camera;
    private MovementInput savedInput;

    private boolean playing;
    private int playTick;

    /** Tick length of each segment, derived from its length and its destination's speed tier. */
    private int[] segmentTicks;
    private int totalTicks;

    public SculptorCameraController() {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(ADD_SLOW_KEY);
        ClientRegistry.registerKeyBinding(ADD_NORMAL_KEY);
        ClientRegistry.registerKeyBinding(ADD_FAST_KEY);
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

        if (ADD_SLOW_KEY.isPressed()) addKeyframe(mc, PathSpeed.SLOW);
        if (ADD_NORMAL_KEY.isPressed()) addKeyframe(mc, PathSpeed.NORMAL);
        if (ADD_FAST_KEY.isPressed()) addKeyframe(mc, PathSpeed.FAST);

        if (PLAY_KEY.isPressed()) {
            togglePlayback(mc);
        }
    }

    private void attach(Minecraft mc) {
        EntityClientPlayerMP player = mc.thePlayer;

        // posY is already the eye anchor for a player in 1.7.10 -- the bounding box hangs
        // below it by yOffset -- so adding eye height again would start the camera high.
        camera = new SculptorCamera(
            mc.theWorld,
            player.posX,
            player.posY,
            player.posZ,
            player.rotationYaw,
            player.rotationPitch);

        mc.renderViewEntity = camera;

        // Freeze the real player: the base MovementInput reads nothing, so WASD reaches the
        // camera only.
        savedInput = player.movementInput;
        player.movementInput = new MovementInput();
        player.motionX = player.motionY = player.motionZ = 0.0D;

        say(mc, "Sculptor: free camera ON  [J/K/M] keyframe slow/normal/fast  [P] play  [Del] clear");
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

    private void addKeyframe(Minecraft mc, PathSpeed speed) {
        if (!isActive()) {
            say(mc, "Sculptor: enable the free camera first");
            return;
        }
        path.add(new Keyframe(camera.posX, camera.posY, camera.posZ, camera.rotationYaw, camera.rotationPitch, speed));
        say(mc, "Sculptor: keyframe " + path.size() + " added (" + speed.label() + " approach)");
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

        computeSegmentTimings();
        playing = true;
        playTick = 0;
        say(mc, String.format("Sculptor: playing %d keyframes over %.1fs", path.size(), totalTicks / 20.0D));
    }

    /**
     * Work out how long each segment should take.
     *
     * <p>
     * A segment's duration is its length divided by the speed of the keyframe it arrives at, so
     * the camera holds a constant rate rather than a constant time. That is what makes a long
     * establishing move and a short detail move feel like the same camera.
     * </p>
     */
    private void computeSegmentTimings() {
        CameraSettings settings = CameraSettings.instance();
        int segments = path.size() - 1;

        segmentTicks = new int[segments];
        totalTicks = 0;

        for (int i = 0; i < segments; i++) {
            Keyframe from = path.get(i);
            Keyframe to = path.get(i + 1);

            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double blocksPerSecond = settings.getPathSpeed(to.speed);
            // A segment with no travel still needs a tick to turn the camera through, so this
            // never rounds down to zero.
            int ticks = (int) Math.max(1, Math.round(distance / blocksPerSecond * TICKS_PER_SECOND));

            segmentTicks[i] = ticks;
            totalTicks += ticks;
        }
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

        syncViewState(mc);

        if (playing) {
            advancePlayback(mc);
        } else {
            applyFreeMovement(mc);
        }
    }

    /**
     * Copy the player's potion effects and creative flag onto the camera each tick.
     *
     * <p>
     * Plenty of the render path is keyed on {@code renderViewEntity} rather than the player:
     * {@code updateFogColor} gates night vision on it, so fog and sky stay dark without this;
     * {@code setupFog} reads {@code capabilities.isCreativeMode} off it, so void fog appears for
     * a creative player; and Angelica's Iris {@code CommonUniforms} reads it to drive the
     * shader's night-vision uniform, which is where the effect is most visibly lost.
     * </p>
     *
     * <p>
     * This resyncs every tick rather than copying once on attach, for three reasons. The camera
     * is never added to the world and never ticked, so durations would freeze -- and night
     * vision flickers below 200 ticks remaining, so a frozen duration either flickers forever or
     * never does. {@code addPotionEffect} combines with an existing effect rather than replacing
     * it, so re-adding without clearing would inflate amplifiers. And {@code updateFogColor}
     * gates on the camera but then calls {@code getNightVisionBrightness(mc.thePlayer, ...)},
     * which dereferences the player's effect unguarded -- camera-has-it while player-does-not is
     * a hard crash in the render loop, which resyncing from the player makes impossible.
     * </p>
     */
    private void syncViewState(Minecraft mc) {
        camera.capabilities.isCreativeMode = mc.thePlayer.capabilities.isCreativeMode;

        camera.clearActivePotions();
        for (Object effect : mc.thePlayer.getActivePotionEffects()) {
            camera.addPotionEffect(new PotionEffect((PotionEffect) effect));
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

        CameraSettings settings = CameraSettings.instance();
        double speed = settings.getFlySpeed() / TICKS_PER_SECOND;
        if (mc.gameSettings.keyBindSprint.getIsKeyPressed()) {
            speed *= settings.getSprintMultiplier();
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
        if (playTick >= totalTicks) {
            stopPlayback(mc, "Sculptor: playback finished");
            return;
        }

        // Walk the cumulative timings to find which segment this tick falls in. Segments have
        // different lengths now, so this cannot be a division.
        int segment = 0;
        int ticksIntoSegment = playTick;
        while (segment < segmentTicks.length - 1 && ticksIntoSegment >= segmentTicks[segment]) {
            ticksIntoSegment -= segmentTicks[segment];
            segment++;
        }

        double t = ticksIntoSegment / (double) segmentTicks[segment];

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

        String line;
        if (playing) {
            line = String.format("Camera  playing  %.1fs / %.1fs", playTick / 20.0D, totalTicks / 20.0D);
        } else {
            line = String.format(
                "Camera  free  %.0f b/s  %d keyframes  [J/K/M] add  [P] play  [Del] clear",
                CameraSettings.instance()
                    .getFlySpeed(),
                path.size());
        }

        mc.fontRenderer.drawStringWithShadow(line, 4, res.getScaledHeight() - 32, 0xFF66CCFF);
    }

    private static void say(Minecraft mc, String message) {
        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }

}
