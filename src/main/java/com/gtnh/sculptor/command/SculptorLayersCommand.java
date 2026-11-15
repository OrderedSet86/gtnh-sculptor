package com.gtnh.sculptor.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.layers.LayerFill;
import com.gtnh.sculptor.layers.LayerStack;
import com.gtnh.sculptor.layers.LayerSyntaxException;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;
import com.sk89q.worldedit.regions.Region;

/**
 * The server command {@code /sclayers}. The command fills the WorldEdit selection of the player in bands of altitude.
 * The fill is recorded in the WorldEdit session, so {@code //undo} reverts the fill.
 *
 * <pre>
 *     /sclayers 10:stone *:grass 10:bedrock -s 3
 *     Sculptor: layered 5120 blocks
 * </pre>
 */
public class SculptorLayersCommand extends CommandBase {

    private static final String PERMISSION_BRUSH = "sculptor.brush";
    private static final String SPILL_FLAG = "-s";
    private static final String USAGE = "sculptor.layers.usage";

    @Override
    public String getCommandName() {
        return "sclayers";
    }

    // the command map of vanilla matches the whole word, so `//sclayers` is a separate alias
    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("/sclayers");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    // the default of vanilla is op level 4. processCommand() checks the permission through the permission provider of
    // WorldEdit, so a permissions mod can give Sculptor to a player who is not an op
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    /**
     * Fill the selection of the player, and report the count of changed blocks in chat.
     *
     * @throws CommandException with the lang key of the failure, for a red chat line. With no layer, the exception is a
     *                          {@link WrongUsageException} with the usage
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("sculptor.command.not_player");

        final EntityPlayerMP playerMP = (EntityPlayerMP) sender;
        final ForgePlayer player = ForgeWorldEdit.inst.wrap(playerMP);
        if (!player.hasPermission(PERMISSION_BRUSH)) throw new CommandException("sculptor.command.no_permission");

        final LocalSession session = ForgeWorldEdit.inst.getSession(playerMP);
        final LayerFill fill = parseFill(args, player, session);
        final Region selection;
        try {
            selection = session.getSelection(player.getWorld());
        } catch (IncompleteRegionException exception) {
            throw chainCause(new CommandException("sculptor.layers.no_selection"), exception);
        }

        // LocalSession.createEditSession() sets the //gmask and the block limit of the player on the EditSession
        final EditSession edit = session.createEditSession(player);
        try {
            fill.apply(edit, selection);
        } catch (MaxChangedBlocksException exception) {
            throw chainCause(
                new CommandException("sculptor.layers.too_many_blocks", exception.getBlockLimit()),
                exception);
        } finally {
            // the blocks written before a failure are in the session too, so //undo reverts them
            edit.flushQueue();
            session.remember(edit);
        }
        SculptorChat.sendTranslated(sender, "sculptor.layers.done", edit.getBlockChangeCount());
    }

    /**
     * Parse the layers, with WorldEdit's block parser, and the spill after {@code -s}.
     *
     * @throws CommandException with the lang key of the failure, for a red chat line. With no layer, the exception is a
     *                          {@link WrongUsageException} with the usage
     */
    private static LayerFill parseFill(String[] args, ForgePlayer player, LocalSession session)
        throws WrongUsageException {
        final List<String> rawLayers = new ArrayList<>();
        int spill = 0;
        for (int index = 0; index < args.length; index++) {
            if (!args[index].equals(SPILL_FLAG)) {
                rawLayers.add(args[index]);
                continue;
            }
            if (index + 1 == args.length) throw new CommandException("sculptor.layers.missing_spill");
            spill = parseSpill(args[++index]);
        }
        if (rawLayers.isEmpty()) throw new WrongUsageException(USAGE);

        final ParserContext context = new ParserContext();
        context.setActor(player);
        context.setWorld(player.getWorld());
        context.setSession(session);
        context.setRestricted(true);
        try {
            final LayerStack stack = LayerStack.parse(
                rawLayers,
                rawBlock -> WorldEdit.getInstance()
                    .getBlockFactory()
                    .parseFromInput(rawBlock, context));
            return new LayerFill(
                stack,
                spill,
                ThreadLocalRandom.current()
                    .nextInt());
        } catch (LayerSyntaxException exception) {
            throw chainCause(new CommandException(exception.getLangKey(), exception.getFormatValues()), exception);
        }
    }

    private static int parseSpill(String rawSpill) {
        final int spill;
        try {
            spill = Integer.parseInt(rawSpill);
        } catch (NumberFormatException exception) {
            throw chainCause(new CommandException("sculptor.layers.bad_spill", rawSpill), exception);
        }
        if (spill < 0 || spill > LayerFill.MAX_SPILL) throw new CommandException("sculptor.layers.bad_spill", rawSpill);
        return spill;
    }

    // CommandException has no constructor with a cause
    private static CommandException chainCause(CommandException failure, Throwable cause) {
        failure.initCause(cause);
        return failure;
    }
}
