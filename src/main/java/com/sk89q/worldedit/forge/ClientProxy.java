/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
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

package com.sk89q.worldedit.forge;

import net.minecraftforge.common.MinecraftForge;

import com.gtnh.sculptor.camera.SculptorCameraController;
import com.gtnh.sculptor.client.SculptorBrushTool;
import com.gtnh.sculptor.render.GhostRenderer;

import cpw.mods.fml.common.FMLCommonHandler;

public class ClientProxy extends CommonProxy {

    @Override
    public void registerHandlers() {
        super.registerHandlers();
        FMLCommonHandler.instance()
            .bus()
            .register(new KeyHandler());

        // Sculptor. Key input and ticks arrive on the FML bus; mouse, overlay and
        // RenderWorldLastEvent on the Forge bus, so the brush tool needs both.
        SculptorBrushTool brushTool = new SculptorBrushTool();
        FMLCommonHandler.instance()
            .bus()
            .register(brushTool);
        MinecraftForge.EVENT_BUS.register(brushTool);
        MinecraftForge.EVENT_BUS.register(GhostRenderer.instance());

        SculptorCameraController camera = new SculptorCameraController();
        FMLCommonHandler.instance()
            .bus()
            .register(camera);
        MinecraftForge.EVENT_BUS.register(camera);
    }

}
