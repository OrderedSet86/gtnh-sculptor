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

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;
import com.sk89q.worldedit.patterns.SingleBlockPattern;

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
 * request, not a write: the client sends what brush it used and where, and the server replays
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

    private static final byte OP_COMMIT_SPHERE = 1;

    /** Brush radius is clamped server-side; a client is not trusted to bound its own edits. */
    private static final int MAX_RADIUS = 64;

    private static FMLEventChannel channel;

    private static final Queue<PendingCommit> queue = new ConcurrentLinkedQueue<PendingCommit>();

    private static final class PendingCommit {

        final EntityPlayerMP player;
        final int x, y, z, radius;

        PendingCommit(EntityPlayerMP player, int x, int y, int z, int radius) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
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

    /** Client side: ask the server to apply a sphere. */
    public static void sendCommitSphere(int x, int y, int z, int radius) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(OP_COMMIT_SPHERE);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(radius);

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
        if (op != OP_COMMIT_SPHERE || buf.readableBytes() < 16) return;

        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        int radius = buf.readInt();

        if (radius < 1 || radius > MAX_RADIUS) return;

        EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
        queue.add(new PendingCommit(player, x, y, z, radius));
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
                edit.makeSphere(
                    new Vector(commit.x, commit.y, commit.z),
                    new SingleBlockPattern(new BaseBlock(BlockID.STONE)),
                    commit.radius,
                    true);
            } finally {
                edit.flushQueue();
                // Registering with the session is what puts this edit on the undo stack.
                session.remember(edit);
            }

            playerMP
                .addChatMessage(new ChatComponentText("Sculptor: applied " + edit.getBlockChangeCount() + " blocks"));
        } catch (Exception e) {
            playerMP.addChatMessage(new ChatComponentText("Sculptor: commit failed: " + e));
            e.printStackTrace();
        }
    }

}
