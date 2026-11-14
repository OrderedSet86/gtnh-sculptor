package com.gtnh.sculptor;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnh.sculptor.command.SculptorCommand;
import com.gtnh.sculptor.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * The Forge entry point of Sculptor, an interactive creative world editor that adds brushes, a live preview and a free
 * camera to WorldEdit.
 *
 * <p>
 * Sculptor uses the edit engine of WorldEdit, and registers only the commands {@code /scu}, {@code /scr} and
 * {@code /scpath}. The undo history of Sculptor is separate from the session history of WorldEdit.
 * </p>
 */
@Mod(
    modid = SculptorMod.MOD_ID,
    name = "Sculptor",
    version = SculptorTags.VERSION,
    acceptableRemoteVersions = "*",
    // without WorldEdit, Sculptor has no edit engine. Fail at load time, not on the first stroke
    dependencies = "required-after:worldedit")
public class SculptorMod {

    public static final String MOD_ID = "sculptor";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.Instance(MOD_ID)
    public static SculptorMod instance;

    @SidedProxy(serverSide = "com.gtnh.sculptor.proxy.CommonProxy", clientSide = "com.gtnh.sculptor.proxy.ClientProxy")
    public static CommonProxy proxy;

    // set once in preInit(). Forge gives the directory only through the pre-init event
    private static Path configDirectory;

    /**
     * Take the configuration directory from Forge. A pack can move the directory away from {@code .minecraft/config}.
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDirectory = event.getModConfigurationDirectory()
            .toPath();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.registerHandlers();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(SculptorCommand.undoCommand());
        event.registerServerCommand(SculptorCommand.redoCommand());
    }

    /** The Forge configuration directory, or {@code null} before pre-init. */
    public static Path getConfigDirectory() {
        return configDirectory;
    }

}
