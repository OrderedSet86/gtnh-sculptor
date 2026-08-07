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

package com.gtnh.sculptor;

import java.io.File;

import com.gtnh.sculptor.command.SculptorCommand;
import com.gtnh.sculptor.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Sculptor: an interactive creative world editor.
 *
 * <p>
 * Sculptor is an <em>addon</em> to WorldEdit, not a replacement for it. It uses WorldEdit's
 * edit engine -- {@code EditSession}, selections, masks, patterns -- but registers no commands
 * of its own and never touches WorldEdit's session history. A server can run the real WorldEdit
 * with every existing workflow intact and drop Sculptor in beside it.
 * </p>
 *
 * <p>
 * The dependency is required rather than optional: Sculptor has no edit engine of its own, so
 * loading without WorldEdit would leave it unable to do anything at all. Declaring it
 * {@code required-after} makes that a clear load-time error instead of a puzzling failure the
 * first time someone presses a brush key.
 * </p>
 */
@Mod(
    modid = SculptorMod.MOD_ID,
    name = "Sculptor",
    version = SculptorTags.VERSION,
    acceptableRemoteVersions = "*",
    dependencies = "required-after:worldedit")
public class SculptorMod {

    public static final String MOD_ID = "sculptor";

    @Mod.Instance(MOD_ID)
    public static SculptorMod instance;

    @SidedProxy(serverSide = "com.gtnh.sculptor.proxy.CommonProxy", clientSide = "com.gtnh.sculptor.proxy.ClientProxy")
    public static CommonProxy proxy;

    private static File configDirectory;

    /**
     * Forge hands over the configuration directory here and nowhere else.
     *
     * <p>
     * Taken from the event rather than assumed to be {@code .minecraft/config}, because a pack can
     * move it -- and 0.1.0 already shipped one bug caused by tying a directory to the wrong root.
     * </p>
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDirectory = event.getModConfigurationDirectory();
    }

    /** Where Sculptor keeps its own files. Null before pre-init. */
    public static File configDirectory() {
        return configDirectory;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.registerHandlers();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // Sculptor's undo is separate from WorldEdit's, so it needs its own commands.
        event.registerServerCommand(SculptorCommand.undoCommand());
        event.registerServerCommand(SculptorCommand.redoCommand());
    }

}
