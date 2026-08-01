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

package com.gtnh.sculptor.render;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VertexBufferType;
import com.gtnh.sculptor.preview.PreviewWorld;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.blocks.BaseBlock;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws a set of pending block changes as a translucent ghost in the world.
 *
 * <p>
 * The geometry is produced by running vanilla {@link RenderBlocks} against a
 * {@link PreviewWorld}, so previewed blocks get their real models and textures, and faces are
 * culled against existing terrain. The resulting quads are captured into a VBO via GTNHLib's
 * tessellator capture rather than drawn immediately, so a stroke is baked once and then
 * redrawn every frame for free.
 * </p>
 *
 * <p>
 * We deliberately do not touch the terrain renderer. Angelica {@code @Overwrite}s
 * {@code RenderGlobal.sortAndRender} and friends at priority 900; drawing from
 * {@link RenderWorldLastEvent} stays entirely out of its way, and means previewing costs zero
 * chunk rebuilds -- nothing is written to the world until the user commits.
 * </p>
 */
public class GhostRenderer {

    private static final GhostRenderer INSTANCE = new GhostRenderer();

    /** How solid the ghost appears. */
    private static final float ALPHA = 0.55f;

    private Map<BlockVector, BaseBlock> pending;
    private IVertexArrayObject mesh;
    private boolean needsRebuild;

    /**
     * Integer world position the mesh was baked relative to. Baking around a nearby origin
     * keeps vertex coordinates small, which matters because they are floats and world
     * coordinates can be in the millions.
     */
    private int originX, originY, originZ;

    /** Draw the ghost through terrain (x-ray) rather than letting it be occluded. */
    private boolean depthTest = true;

    private GhostRenderer() {}

    public static GhostRenderer instance() {
        return INSTANCE;
    }

    /**
     * Show the given pending changes. The map is used directly rather than copied, so callers
     * should not mutate it afterwards; {@code EditSession.getPendingChanges()} already returns
     * an unmodifiable view.
     */
    public void setPreview(Map<BlockVector, BaseBlock> changes) {
        this.pending = changes;
        this.needsRebuild = true;
    }

    /** Stop drawing and release the GPU buffer. */
    public void clear() {
        this.pending = null;
        this.needsRebuild = false;
        deleteMesh();
    }

    public void setDepthTest(boolean depthTest) {
        this.depthTest = depthTest;
    }

    public boolean hasPreview() {
        return pending != null && !pending.isEmpty();
    }

    private void deleteMesh() {
        if (mesh != null) {
            mesh.delete();
            mesh = null;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!hasPreview()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.renderViewEntity == null) return;

        mc.mcProfiler.startSection("sculptorGhost");
        try {
            if (needsRebuild) {
                rebuild(mc);
                needsRebuild = false;
            }
            if (mesh != null) {
                draw(mc, event.partialTicks);
            }
        } finally {
            mc.mcProfiler.endSection();
        }
    }

    /**
     * Bake the pending changes into a VBO.
     *
     * <p>
     * This runs on the render thread. Moving it to a worker is the obvious optimisation, but
     * arbitrary mod {@code ISBRH} implementations are not thread-safe, so that step needs
     * {@code AngelicaBlockSafetyRegistry} gating and is deferred until this path is known
     * correct.
     * </p>
     */
    private void rebuild(Minecraft mc) {
        deleteMesh();

        Entity view = mc.renderViewEntity;
        originX = (int) Math.floor(view.posX);
        originY = (int) Math.floor(view.posY);
        originZ = (int) Math.floor(view.posZ);

        PreviewWorld world = new PreviewWorld(mc.theWorld, pending);
        RenderBlocks renderBlocks = new RenderBlocks(world);
        // Ambient occlusion reads neighbour light through the fake world and mostly just adds
        // noise to a translucent overlay.
        renderBlocks.enableAO = false;

        DirectTessellator tes = TessellatorManager.startCapturingDirect();
        boolean captured = false;
        try {
            tes.startDrawingQuads();
            tes.setTranslation(-originX, -originY, -originZ);

            for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
                Block block = Block.getBlockById(
                    entry.getValue()
                        .getType());
                if (block == null || block.getMaterial() == Material.air) continue;

                BlockVector pos = entry.getKey();
                renderBlocks.renderBlockByRenderType(block, pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            }

            tes.setTranslation(0, 0, 0);
            mesh = DirectTessellator.stopCapturingToVBO(VertexBufferType.IMMUTABLE);
            captured = true;
        } finally {
            if (!captured) {
                // Never leave the capture stack unbalanced -- it is thread-local state shared
                // with every other mod that tessellates.
                TessellatorManager.stopCapturingDirect();
            }
        }
    }

    private void draw(Minecraft mc, float partialTicks) {
        Entity view = mc.renderViewEntity;
        double viewX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double viewY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double viewZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            mc.renderEngine.bindTexture(TextureMap.locationBlocksTexture);

            // The mesh is in bake-origin space; shift it by how far the camera has moved since.
            GL11.glTranslated(originX - viewX, originY - viewY, originZ - viewZ);

            // RenderBlocks emits opaque vertex colours (setColorOpaque_F), so ordinary
            // SRC_ALPHA blending would draw the ghost solid. Constant-alpha blending applies a
            // uniform transparency independent of what the block models baked in, which avoids
            // needing a shader purely to scale alpha.
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(1f, 1f, 1f, ALPHA);
            GL11.glBlendFunc(GL11.GL_CONSTANT_ALPHA, GL11.GL_ONE_MINUS_CONSTANT_ALPHA);

            // Translucent geometry has no back-face occlusion to rely on, and the quads are not
            // depth-sorted, so keep both sides and do not write depth.
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);

            if (depthTest) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }

            mesh.render();
        } finally {
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

}
