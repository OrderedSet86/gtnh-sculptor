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

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import com.mojang.authlib.GameProfile;

/**
 * A camera the renderer can look through instead of the player.
 *
 * <p>
 * Minecraft renders from {@code Minecraft.renderViewEntity}, which must be an
 * {@code EntityLivingBase}; the cheapest way to get a free camera is therefore to point that
 * field at a throwaway player-shaped entity rather than to intercept the view matrix. The same
 * approach Freecam takes.
 * </p>
 *
 * <p>
 * This entity is never added to the world. It has no collision, takes no input of its own, and
 * exists only to carry a position and orientation that something else drives -- either free
 * movement or path playback.
 * </p>
 */
public class SculptorCamera extends EntityPlayer {

    private static final GameProfile PROFILE = new GameProfile(null, "SculptorCamera");

    public SculptorCamera(World world, double x, double y, double z, float yaw, float pitch) {
        super(world, PROFILE);

        this.noClip = true;
        this.isImmuneToFire = true;
        this.setSize(0.1F, 0.1F);
        this.yOffset = 0.0F;

        setPositionAndRotationDirect(x, y, z, yaw, pitch);
    }

    /**
     * Place the camera and clear its interpolation history.
     *
     * <p>
     * The renderer interpolates between {@code prev*} and current every frame, so a teleport
     * that does not also reset the previous values renders as a smear from wherever the camera
     * used to be.
     * </p>
     */
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch) {
        this.posX = this.prevPosX = this.lastTickPosX = x;
        this.posY = this.prevPosY = this.lastTickPosY = y;
        this.posZ = this.prevPosZ = this.lastTickPosZ = z;
        this.rotationYaw = this.prevRotationYaw = yaw;
        this.rotationPitch = this.prevRotationPitch = pitch;
        this.setPosition(x, y, z);
    }

    /**
     * Move the camera, keeping {@code prev*} as the previous tick's values so partial-tick
     * interpolation smooths the motion for free.
     */
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        this.prevPosX = this.lastTickPosX = this.posX;
        this.prevPosY = this.lastTickPosY = this.posY;
        this.prevPosZ = this.lastTickPosZ = this.posZ;
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;

        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.rotationYaw = yaw;
        this.rotationPitch = pitch;
        this.setPosition(x, y, z);
    }

    @Override
    public float getEyeHeight() {
        // The camera is the eye; there is no body under it to offset from.
        return 0.0F;
    }

    @Override
    public boolean isEntityInvulnerable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isInvisibleToPlayer(EntityPlayer player) {
        return true;
    }

    // --- EntityPlayer abstracts, none of which apply to a disembodied camera ---------------

    @Override
    public void addChatMessage(IChatComponent message) {}

    @Override
    public boolean canCommandSenderUseCommand(int permission, String command) {
        return false;
    }

    @Override
    public ChunkCoordinates getPlayerCoordinates() {
        return new ChunkCoordinates((int) this.posX, (int) this.posY, (int) this.posZ);
    }

}
