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

package com.gtnh.sculptor.command;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.gtnh.sculptor.edit.SculptorHistory;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

/**
 * Undo and redo for Sculptor's own edits.
 *
 * <p>
 * Separate from WorldEdit's {@code //undo}, which walks the player's command history and will
 * not revert a brush stroke. Deliberately a command rather than a keybind: undo is destructive
 * and infrequent, and a key that far-reaching is too easy to hit by accident while sculpting.
 * </p>
 *
 * <p>
 * Named for how often it is typed. Undo is reached for constantly while sculpting, and
 * {@code //sc-undo} is four more keystrokes than the operation deserves -- the dash especially,
 * sitting where it does. The primary name is the short {@code /scu}, with {@code //scu} kept as an
 * alias so WorldEdit's double-slash habit still lands.
 * </p>
 */
public class SculptorCommand extends CommandBase {

    private static final String PERMISSION_BRUSH = "sculptor.brush";

    private final boolean undo;

    private SculptorCommand(boolean undo) {
        this.undo = undo;
    }

    public static SculptorCommand undoCommand() {
        return new SculptorCommand(true);
    }

    public static SculptorCommand redoCommand() {
        return new SculptorCommand(false);
    }

    @Override
    public String getCommandName() {
        return undo ? "scu" : "scr";
    }

    /**
     * The {@code /}-prefixed form is a separate map key, not a variant of the name, so
     * {@code //scu} only works if it is registered as its own alias.
     */
    @Override
    public List<String> getCommandAliases() {
        List<String> aliases = new ArrayList<String>();
        aliases.add(undo ? "/scu" : "/scr");
        return aliases;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return undo ? "/scu - undo your last Sculptor edit" : "/scr - redo the Sculptor edit you undid";
    }

    /**
     * Vanilla's default requires op level 4. Authorisation is done in
     * {@link #processCommand} through WorldEdit's permissions provider instead, so that a server
     * running a permissions mod can grant Sculptor without granting op.
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("Sculptor: only a player has an edit history"));
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        try {
            ForgePlayer wrapped = ForgeWorldEdit.inst.wrap(player);
            if (!wrapped.hasPermission(PERMISSION_BRUSH)) {
                sender.addChatMessage(new ChatComponentText("Sculptor: you do not have permission to edit"));
                return;
            }

            SculptorHistory history = SculptorHistory.forPlayer(player.getUniqueID());
            boolean done = undo ? history.undo(wrapped) : history.redo(wrapped);

            if (done) {
                sender.addChatMessage(new ChatComponentText(undo ? "Sculptor: undone" : "Sculptor: redone"));
            } else {
                sender.addChatMessage(
                    new ChatComponentText(undo ? "Sculptor: nothing to undo" : "Sculptor: nothing to redo"));
            }
        } catch (Exception e) {
            sender.addChatMessage(new ChatComponentText("Sculptor: " + (undo ? "undo" : "redo") + " failed: " + e));
            e.printStackTrace();
        }
    }

}
