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
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Keyboard;

import com.gtnh.sculptor.render.GhostRenderer;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.forge.ForgeWorldEdit;
import com.sk89q.worldedit.patterns.SingleBlockPattern;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;

/**
 * Temporary driver for the ghost renderer while the real brush loop is being built.
 *
 * <p>
 * Previews a stone sphere at the block under the crosshair, so the whole path -- preview
 * capture, fake world, bake, draw -- can be exercised in game. This is scaffolding: it will be
 * replaced by the proper brush interaction loop, which tracks the crosshair continuously and
 * commits on click.
 * </p>
 */
public class SculptorKeyHandler {

    private static final KeyBinding PREVIEW_KEY = new KeyBinding(
        "Sculptor: preview sphere",
        Keyboard.KEY_G,
        "Sculptor");
    private static final KeyBinding CLEAR_KEY = new KeyBinding("Sculptor: clear preview", Keyboard.KEY_H, "Sculptor");

    private static final int RADIUS = 5;

    public SculptorKeyHandler() {
        ClientRegistry.registerKeyBinding(PREVIEW_KEY);
        ClientRegistry.registerKeyBinding(CLEAR_KEY);
    }

    @SubscribeEvent
    public void onKey(KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (CLEAR_KEY.isPressed()) {
            GhostRenderer.instance()
                .clear();
            say(mc, "Sculptor: preview cleared");
            return;
        }

        if (!PREVIEW_KEY.isPressed()) return;

        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            say(mc, "Sculptor: look at a block first");
            return;
        }

        try {
            EditSession preview = WorldEdit.getInstance()
                .getEditSessionFactory()
                .getPreviewEditSession(ForgeWorldEdit.inst.getWorld(mc.theWorld), -1);

            preview.makeSphere(
                new Vector(hit.blockX, hit.blockY, hit.blockZ),
                new SingleBlockPattern(new BaseBlock(BlockID.STONE)),
                RADIUS,
                true);

            GhostRenderer.instance()
                .setPreview(preview.getPendingChanges());

            say(
                mc,
                "Sculptor: previewing " + preview.getPendingChanges()
                    .size() + " blocks");
        } catch (Exception e) {
            say(mc, "Sculptor: preview failed: " + e);
            e.printStackTrace();
        }
    }

    private static void say(Minecraft mc, String message) {
        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }

}
