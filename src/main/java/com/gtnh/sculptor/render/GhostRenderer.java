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
import net.minecraft.client.renderer.Tessellator;
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
import com.sk89q.worldedit.blocks.BlockID;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws a set of pending block changes as a translucent ghost in the world.
 *
 * <p>
 * Two meshes are built, because an edit has two halves and only showing one of them is
 * misleading. Blocks being <em>placed</em> are drawn with their real models and textures, via
 * vanilla {@link RenderBlocks} run against a {@link PreviewWorld} so faces are culled against
 * existing terrain. Blocks being <em>removed</em> have no model to draw -- their pending state
 * is air -- so they are drawn as red boxes over the blocks that are about to disappear.
 * Without that, a brush that only removes (lower, erode) would preview as nothing at all.
 * </p>
 *
 * <p>
 * Geometry is captured into a VBO rather than drawn immediately, so a stroke is baked once and
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

    /** How far a removal box is inset into its block, to avoid z-fighting with real terrain. */
    private static final double REMOVAL_INSET = 0.02D;

    private Map<BlockVector, BaseBlock> pending;

    /** Blocks being placed, with their real models. */
    private IVertexArrayObject additionMesh;
    /** Blocks being removed, as plain coloured boxes. */
    private IVertexArrayObject removalMesh;

    private boolean needsRebuild;

    /**
     * Integer world position the meshes were baked relative to. Baking around a nearby origin
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

    /** Stop drawing and release the GPU buffers. */
    public void clear() {
        this.pending = null;
        this.needsRebuild = false;
        deleteMeshes();
    }

    public void setDepthTest(boolean depthTest) {
        this.depthTest = depthTest;
    }

    public boolean hasPreview() {
        return pending != null && !pending.isEmpty();
    }

    private void deleteMeshes() {
        if (additionMesh != null) {
            additionMesh.delete();
            additionMesh = null;
        }
        if (removalMesh != null) {
            removalMesh.delete();
            removalMesh = null;
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
            if (additionMesh != null || removalMesh != null) {
                draw(mc, event.partialTicks);
            }
        } finally {
            mc.mcProfiler.endSection();
        }
    }

    /**
     * Bake the pending changes into VBOs.
     *
     * <p>
     * This runs on the render thread. Moving it to a worker is the obvious optimisation, but
     * arbitrary mod {@code ISBRH} implementations are not thread-safe, so that step needs
     * {@code AngelicaBlockSafetyRegistry} gating and is deferred until this path is known
     * correct.
     * </p>
     */
    private void rebuild(Minecraft mc) {
        deleteMeshes();

        Entity view = mc.renderViewEntity;
        originX = (int) Math.floor(view.posX);
        originY = (int) Math.floor(view.posY);
        originZ = (int) Math.floor(view.posZ);

        PreviewWorld world = new PreviewWorld(mc.theWorld, pending);
        additionMesh = bakeAdditions(world);
        removalMesh = bakeRemovals(mc);
    }

    private IVertexArrayObject bakeAdditions(PreviewWorld world) {
        RenderBlocks renderBlocks = new RenderBlocks(world);
        // Ambient occlusion reads neighbour light through the fake world and mostly just adds
        // noise to a translucent overlay.
        renderBlocks.enableAO = false;

        DirectTessellator tes = TessellatorManager.startCapturingDirect();
        int quads = 0;
        try {
            tes.startDrawingQuads();
            tes.setTranslation(-originX, -originY, -originZ);

            for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
                Block block = Block.getBlockById(
                    entry.getValue()
                        .getType());
                if (block == null || block.getMaterial() == Material.air) continue;

                BlockVector pos = entry.getKey();
                if (renderBlocks.renderBlockByRenderType(block, pos.getBlockX(), pos.getBlockY(), pos.getBlockZ())) {
                    quads++;
                }
            }

            tes.setTranslation(0, 0, 0);
        } catch (RuntimeException e) {
            // Never leave the capture stack unbalanced -- it is thread-local state shared with
            // every other mod that tessellates.
            TessellatorManager.stopCapturingDirect();
            throw e;
        }

        return finishCapture(quads);
    }

    /**
     * Draw a box over every block the edit will delete.
     *
     * <p>
     * A removal's pending state is air, which has no model, so these would otherwise be
     * invisible. Only positions that currently hold something are marked -- carving air out of
     * air is not a change worth showing.
     * </p>
     */
    private IVertexArrayObject bakeRemovals(Minecraft mc) {
        DirectTessellator tes = TessellatorManager.startCapturingDirect();
        int boxes = 0;
        try {
            tes.startDrawingQuads();
            tes.setTranslation(-originX, -originY, -originZ);

            for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
                if (entry.getValue()
                    .getType() != BlockID.AIR) {
                    continue;
                }

                BlockVector pos = entry.getKey();
                Block existing = mc.theWorld.getBlock(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
                if (existing == null || existing.getMaterial() == Material.air) continue;

                addBox(tes, pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
                boxes++;
            }

            tes.setTranslation(0, 0, 0);
        } catch (RuntimeException e) {
            TessellatorManager.stopCapturingDirect();
            throw e;
        }

        return finishCapture(boxes);
    }

    /**
     * Close a capture, returning a mesh only if anything was actually drawn.
     *
     * <p>
     * An empty capture yields a vertex buffer with no format attached, which throws deep inside
     * the VAO code the moment it is bound. Brushes that remove rather than place produce
     * exactly that for the addition pass, so this is a normal case, not an error.
     * </p>
     */
    private static IVertexArrayObject finishCapture(int drawn) {
        if (drawn == 0) {
            TessellatorManager.stopCapturingDirect();
            return null;
        }
        return DirectTessellator.stopCapturingToVBO(VertexBufferType.IMMUTABLE);
    }

    /** Six faces of a slightly inset cube, in red. */
    private static void addBox(Tessellator tes, int bx, int by, int bz) {
        double x0 = bx + REMOVAL_INSET;
        double y0 = by + REMOVAL_INSET;
        double z0 = bz + REMOVAL_INSET;
        double x1 = bx + 1 - REMOVAL_INSET;
        double y1 = by + 1 - REMOVAL_INSET;
        double z1 = bz + 1 - REMOVAL_INSET;

        tes.setColorRGBA_F(1.0f, 0.25f, 0.25f, 1.0f);

        // bottom / top
        tes.addVertex(x0, y0, z0);
        tes.addVertex(x1, y0, z0);
        tes.addVertex(x1, y0, z1);
        tes.addVertex(x0, y0, z1);

        tes.addVertex(x0, y1, z1);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x0, y1, z0);

        // north / south
        tes.addVertex(x0, y0, z0);
        tes.addVertex(x0, y1, z0);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x1, y0, z0);

        tes.addVertex(x1, y0, z1);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x0, y1, z1);
        tes.addVertex(x0, y0, z1);

        // west / east
        tes.addVertex(x0, y0, z1);
        tes.addVertex(x0, y1, z1);
        tes.addVertex(x0, y1, z0);
        tes.addVertex(x0, y0, z0);

        tes.addVertex(x1, y0, z0);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x1, y0, z1);
    }

    private void draw(Minecraft mc, float partialTicks) {
        Entity view = mc.renderViewEntity;
        double viewX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double viewY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double viewZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            // The meshes are in bake-origin space; shift by how far the camera has moved since.
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

            // A ghost block sits exactly on the face of the real block it covers, and two
            // coplanar surfaces at equal depth shimmer as the rasteriser picks between them per
            // pixel. Bias the ghost toward the viewer in depth only -- unlike inflating the mesh,
            // this fixes the fighting without distorting the geometry or leaving seams where two
            // inflated blocks meet.
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-1.0f, -1.0f);

            if (depthTest) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }

            if (additionMesh != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                mc.renderEngine.bindTexture(TextureMap.locationBlocksTexture);
                additionMesh.render();
            }

            if (removalMesh != null) {
                // Removal boxes carry their own colour and no texture coordinates.
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                removalMesh.render();
            }
        } finally {
            GL11.glPolygonOffset(0.0f, 0.0f);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

}
