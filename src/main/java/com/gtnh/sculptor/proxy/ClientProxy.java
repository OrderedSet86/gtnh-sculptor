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

package com.gtnh.sculptor.proxy;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import com.gtnh.sculptor.camera.SculptorCameraController;
import com.gtnh.sculptor.client.SculptorBrushTool;
import com.gtnh.sculptor.command.SculptorPathCommand;
import com.gtnh.sculptor.render.GhostRenderer;

import cpw.mods.fml.common.FMLCommonHandler;

public class ClientProxy extends CommonProxy {

    @Override
    public void registerHandlers() {
        super.registerHandlers();

        // Key input and ticks arrive on the FML bus; mouse, overlay and RenderWorldLastEvent on
        // the Forge bus, so the tools that use both need registering twice.
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

        MinecraftForge.EVENT_BUS.register(GhostRenderer.instance());

        // Camera paths are client state, so the command that saves them has to be a client
        // command -- the server never sees a path and would have nothing to save.
        ClientCommandHandler.instance.registerCommand(new SculptorPathCommand());
    }

}
