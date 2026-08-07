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
import net.minecraft.util.ChatComponentText;

import com.gtnh.sculptor.camera.CameraKeyframe;
import com.gtnh.sculptor.camera.CameraPathStore;
import com.gtnh.sculptor.camera.SculptorCameraController;

/**
 * Save, load, list and delete camera paths.
 *
 * <p>
 * A <em>client</em> command, registered on Forge's {@code ClientCommandHandler} rather than with
 * the server. The path is client state -- the camera never existed on the server, and in
 * multiplayer the server has nothing to say about it -- so a server command could not reach it,
 * and would stop working the moment you joined a vanilla-ish server that does not run Sculptor.
 * </p>
 */
public class SculptorPathCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "scpath";
    }

    @Override
    public List<String> getCommandAliases() {
        List<String> aliases = new ArrayList<String>();
        aliases.add("/scpath");
        return aliases;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/scpath save|load|delete <name> | /scpath list";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                CameraPathStore.subcommands()
                    .toArray(new String[0]));
        }
        if (args.length == 2 && !"list".equalsIgnoreCase(args[0])) {
            List<String> names = CameraPathStore.instance()
                .list();
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        SculptorCameraController camera = SculptorCameraController.instance();
        if (camera == null) {
            say(sender, "Sculptor: the camera is not available");
            return;
        }

        CameraPathStore store = CameraPathStore.instance();

        if (args.length == 0) {
            say(sender, "Sculptor: " + getCommandUsage(sender));
            return;
        }

        String action = args[0].toLowerCase();

        if ("list".equals(action)) {
            List<String> names = store.list();
            say(sender, names.isEmpty() ? "Sculptor: no saved paths" : "Sculptor: saved paths -- " + join(names));
            return;
        }

        if (args.length < 2) {
            say(sender, "Sculptor: " + getCommandUsage(sender));
            return;
        }

        String name = args[1];
        if (!CameraPathStore.isValidName(name)) {
            say(sender, "Sculptor: '" + name + "' is not a usable name -- letters, digits, _ and - only");
            return;
        }

        try {
            if ("save".equals(action)) {
                List<CameraKeyframe> path = camera.getPath();
                if (path.isEmpty()) {
                    say(sender, "Sculptor: nothing to save -- add some keyframes first");
                    return;
                }
                store.save(name, path);
                say(sender, "Sculptor: saved " + path.size() + " keyframes as '" + name + "'");

            } else if ("load".equals(action)) {
                List<CameraKeyframe> path = store.load(name);
                camera.setPath(path);
                say(sender, "Sculptor: loaded '" + name + "' -- " + path.size() + " keyframes");

            } else if ("delete".equals(action)) {
                say(
                    sender,
                    store.delete(name) ? "Sculptor: deleted '" + name + "'"
                        : "Sculptor: no saved path called '" + name + "'");

            } else {
                say(sender, "Sculptor: " + getCommandUsage(sender));
            }
        } catch (Exception e) {
            // A failed save or load must not take the client down with it.
            say(sender, "Sculptor: " + action + " failed: " + e.getMessage());
        }
    }

    private static String join(List<String> names) {
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(name);
        }
        return joined.toString();
    }

    private static void say(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
