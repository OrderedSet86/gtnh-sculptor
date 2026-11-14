package com.gtnh.sculptor.command;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.edit.SculptorHistory;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

/**
 * The server commands {@code /scu} and {@code /scr}, which undo and redo the brush strokes of the player in
 * {@link SculptorHistory}. The {@code //undo} of WorldEdit does not revert a brush stroke.
 *
 * <p>
 * Undo is a command and not a key, so a stray key press cannot undo a stroke. The aliases {@code //scu} and
 * {@code //scr} accept the double slash of WorldEdit.
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

    // the command map of vanilla matches the whole word, so `//scu` is a separate alias
    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList(undo ? "/scu" : "/scr");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return undo ? "sculptor.command.undo.usage" : "sculptor.command.redo.usage";
    }

    // the default of vanilla is op level 4. processCommand() checks the permission through the permission provider of
    // WorldEdit, so a permissions mod can give Sculptor to a player who is not an op
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("sculptor.command.not_player");

        final EntityPlayerMP player = (EntityPlayerMP) sender;
        final ForgePlayer wrapped = ForgeWorldEdit.inst.wrap(player);
        if (!wrapped.hasPermission(PERMISSION_BRUSH)) throw new CommandException("sculptor.command.no_permission");

        final SculptorHistory history = SculptorHistory.forPlayer(player.getUniqueID());
        if (undo) {
            SculptorChat.sendTranslated(sender, history.undo(wrapped) ? "sculptor.undo.done" : "sculptor.undo.empty");
        } else {
            SculptorChat.sendTranslated(sender, history.redo(wrapped) ? "sculptor.redo.done" : "sculptor.redo.empty");
        }
    }

}
