package com.gtnh.sculptor.command;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;

import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.camera.CameraKeyframe;
import com.gtnh.sculptor.camera.CameraPathException;
import com.gtnh.sculptor.camera.CameraPathStore;
import com.gtnh.sculptor.camera.SculptorCameraController;

/**
 * The client command {@code /scpath}, which saves, loads, lists and deletes camera paths.
 *
 * <p>
 * A camera path exists only on the client, so the command is registered on the {@code ClientCommandHandler} of Forge.
 * The command then also works on a server that does not run Sculptor.
 * </p>
 */
public class SculptorPathCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "scpath";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("/scpath");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "sculptor.path.usage";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, CameraPathStore.SUBCOMMANDS.toArray(new String[0]));
        }
        if (args.length == 2 && !"list".equalsIgnoreCase(args[0])) {
            try {
                return getListOfStringsMatchingLastWord(
                    args,
                    CameraPathStore.instance()
                        .listNames()
                        .toArray(new String[0]));
            } catch (CameraPathException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * Run {@code /scpath save|load|delete <name>} or {@code /scpath list}.
     *
     * @throws WrongUsageException with no action, an unknown action, or no name
     * @throws CommandException    with the lang key of the failure, for a red chat line
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final SculptorCameraController camera = SculptorCameraController.instance();
        if (camera == null) throw new CommandException("sculptor.path.no_camera");
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));

        final CameraPathStore store = CameraPathStore.instance();
        final String action = args[0].toLowerCase(Locale.ROOT);
        try {
            if ("list".equals(action)) {
                final List<String> names = store.listNames();
                if (names.isEmpty()) {
                    SculptorChat.sendTranslated(sender, "sculptor.path.list.empty");
                } else {
                    SculptorChat.sendTranslated(sender, "sculptor.path.list", String.join(", ", names));
                }
                return;
            }

            if (args.length < 2) throw new WrongUsageException(getCommandUsage(sender));
            final String name = args[1];
            if (!CameraPathStore.isValidName(name)) throw new CommandException("sculptor.path.bad_name", name);

            switch (action) {
                case "save" -> {
                    final List<CameraKeyframe> path = camera.getPath();
                    if (path.isEmpty()) throw new CommandException("sculptor.path.nothing_to_save");
                    store.save(name, path);
                    SculptorChat.sendTranslated(sender, "sculptor.path.saved", path.size(), name);
                }
                case "load" -> {
                    final List<CameraKeyframe> path = store.load(name);
                    camera.setPath(path);
                    SculptorChat.sendTranslated(sender, "sculptor.path.loaded", name, path.size());
                }
                case "delete" -> SculptorChat.sendTranslated(
                    sender,
                    store.delete(name) ? "sculptor.path.deleted" : "sculptor.path.missing",
                    name);
                default -> throw new WrongUsageException(getCommandUsage(sender));
            }
        } catch (CameraPathException exception) {
            final CommandException failure = new CommandException(exception.getLangKey(), exception.getFormatValues());
            failure.initCause(exception);
            throw failure;
        }
    }
}
