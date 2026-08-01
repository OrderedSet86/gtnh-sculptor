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

package com.gtnh.sculptor.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentText;

import com.gtnh.sculptor.brush.SculptorBrush;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent.ServerCustomPacketEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Carries brush commits from the client to the server.
 *
 * <p>
 * The client previews against its own copy of the world, but the server owns the blocks --
 * true even in singleplayer, where an integrated server is still running. So a commit is a
 * request, not a write: the client sends which brush it used and where, and the server replays
 * it against a real {@link EditSession} bound to the player's {@link LocalSession}, which is
 * what makes {@code //undo} work afterwards.
 * </p>
 *
 * <p>
 * Packets arrive on a netty thread. World modification happens on the server thread, so
 * commits are queued here and drained on the next server tick rather than applied inline.
 * </p>
 */
public class SculptorNetwork {

    public static final String CHANNEL_NAME = "Sculptor";

    private static final byte OP_COMMIT = 1;

    /** byte brush + 3 ints pos + int radius + short id + byte data + short secondary + byte strength. */
    private static final int COMMIT_PAYLOAD_BYTES = 1 + 12 + 4 + 2 + 1 + 2 + 1;

    /** Brush radius is clamped server-side; a client is not trusted to bound its own edits. */
    private static final int MAX_RADIUS = 64;

    /** Upper bound for a brush's second parameter, whatever that brush means by it. */
    private static final int MAX_SECONDARY = 64;

    /**
     * How far from the player a commit may land, in blocks.
     *
     * <p>
     * The client brush reaches 160 blocks, so this has to allow that, but it should not allow
     * arbitrary edits anywhere in the world from a crafted packet. Squared to avoid a square
     * root per commit.
     * </p>
     */
    private static final double MAX_COMMIT_DISTANCE_SQ = 192.0D * 192.0D;

    private static FMLEventChannel channel;

    private static final Queue<PendingCommit> queue = new ConcurrentLinkedQueue<PendingCommit>();

    private static final class PendingCommit {

        final EntityPlayerMP player;
        final SculptorBrush brush;
        final int x, y, z, radius, materialId, materialData, secondary;
        final double strength;

        PendingCommit(EntityPlayerMP player, SculptorBrush brush, int x, int y, int z, int radius, int materialId,
            int materialData, int secondary, double strength) {
            this.player = player;
            this.brush = brush;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.materialId = materialId;
            this.materialData = materialData;
            this.secondary = secondary;
            this.strength = strength;
        }
    }

    public static void init() {
        SculptorNetwork handler = new SculptorNetwork();
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL_NAME);
        // Packet events come from the channel; the tick we drain on comes from the FML bus.
        channel.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    /** Client side: ask the server to apply the given brush. */
    public static void sendCommit(SculptorBrush brush, int x, int y, int z, int radius, int materialId,
        int materialData, int secondary, double strength) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(OP_COMMIT);
        buf.writeByte(brush.ordinal());
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(radius);
        buf.writeShort(materialId);
        buf.writeByte(materialData);
        buf.writeShort(secondary);
        // Strength is 0..1; a byte of hundredths is finer than the control that produces it.
        buf.writeByte((int) Math.round(strength * 100.0D));

        channel.sendToServer(new FMLProxyPacket(buf, CHANNEL_NAME));
    }

    @SubscribeEvent
    public void onServerPacket(ServerCustomPacketEvent event) {
        if (!CHANNEL_NAME.equals(event.packet.channel())) {
            return;
        }

        ByteBuf buf = event.packet.payload();
        if (buf.readableBytes() < 1) return;

        byte op = buf.readByte();
        if (op != OP_COMMIT || buf.readableBytes() < COMMIT_PAYLOAD_BYTES) return;

        SculptorBrush brush = SculptorBrush.fromOrdinal(buf.readByte());
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        int radius = buf.readInt();
        int materialId = buf.readShort() & 0xFFFF;
        int materialData = buf.readByte() & 0xF;
        int secondary = buf.readShort();
        double strength = Math.max(0.05D, Math.min(1.0D, (buf.readByte() & 0xFF) / 100.0D));

        if (radius < 1 || radius > MAX_RADIUS) return;
        if (secondary < 0 || secondary > MAX_SECONDARY) return;

        EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
        if (player == null) return;

        double dx = player.posX - (x + 0.5D);
        double dy = player.posY - (y + 0.5D);
        double dz = player.posZ - (z + 0.5D);
        if (dx * dx + dy * dy + dz * dz > MAX_COMMIT_DISTANCE_SQ) return;

        queue.add(new PendingCommit(player, brush, x, y, z, radius, materialId, materialData, secondary, strength));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        PendingCommit commit;
        while ((commit = queue.poll()) != null) {
            apply(commit);
        }
    }

    private static void apply(PendingCommit commit) {
        EntityPlayerMP playerMP = commit.player;
        try {
            ForgePlayer player = ForgeWorldEdit.inst.wrap(playerMP);
            LocalSession session = ForgeWorldEdit.inst.getSession(playerMP);

            EditSession edit = session.createEditSession(player);
            try {
                commit.brush.apply(
                    edit,
                    new Vector(commit.x, commit.y, commit.z),
                    commit.radius,
                    new BaseBlock(commit.materialId, commit.materialData),
                    commit.secondary,
                    commit.strength);
            } finally {
                edit.flushQueue();
                // Registering with the session is what puts this edit on the undo stack.
                session.remember(edit);
            }

            playerMP.addChatMessage(
                new ChatComponentText(
                    "Sculptor: " + commit.brush.label() + " applied " + edit.getBlockChangeCount() + " blocks"));
        } catch (Exception e) {
            playerMP.addChatMessage(new ChatComponentText("Sculptor: commit failed: " + e));
            e.printStackTrace();
        }
    }

}
