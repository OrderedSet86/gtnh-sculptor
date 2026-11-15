package com.gtnh.sculptor.proxy;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import com.gtnh.sculptor.camera.SculptorCameraController;
import com.gtnh.sculptor.client.SculptorBrushTool;
import com.gtnh.sculptor.client.SculptorHud;
import com.gtnh.sculptor.command.SculptorPathCommand;
import com.gtnh.sculptor.render.GhostRenderer;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * Registers the client-only handlers: the brush tool, the free camera, the HUD, the ghost renderer and {@code /scpath}.
 */
public class ClientProxy extends CommonProxy {

    /** Register each client handler on the buses with the events of the handler. */
    @Override
    public void registerHandlers() {
        super.registerHandlers();

        // Forge 1.7.10 posts key input and ticks on the FML bus, and mouse, overlay and render events on the Forge bus.
        // Register a tool with handlers of both kinds on both buses
        SculptorBrushTool brushTool = new SculptorBrushTool();
        FMLCommonHandler.instance()
            .bus()
            .register(brushTool);
        MinecraftForge.EVENT_BUS.register(brushTool);

        SculptorCameraController camera = new SculptorCameraController();
        FMLCommonHandler.instance()
            .bus()
            .register(camera);
        MinecraftForge.EVENT_BUS.register(camera);

        MinecraftForge.EVENT_BUS.register(new SculptorHud(brushTool, camera));
        MinecraftForge.EVENT_BUS.register(GhostRenderer.instance());

        // a camera path never reaches the server, so the command that saves a path is a client command
        ClientCommandHandler.instance.registerCommand(new SculptorPathCommand());
    }

}
