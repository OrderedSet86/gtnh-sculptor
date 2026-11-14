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
 * The preview of a brush stroke in the world: a translucent ghost of the placed blocks, and red boxes over the removed
 * blocks.
 *
 * <p>
 * A placed block is drawn with the model of the block, by vanilla {@link RenderBlocks} on a {@link PreviewWorld}, so
 * faces against the terrain are culled. A removed block is air in the preview and has no model, so the block gets a red
 * box. Without the boxes, a brush that only removes, such as lower or erode, shows nothing.
 * </p>
 *
 * <p>
 * {@link #onRenderWorldLast} bakes each preview once into a VBO, and renders the VBO each frame. The class does not
 * change the terrain renderer, which Angelica replaces with {@code @Overwrite} at priority 900. A preview causes no
 * chunk rebuild.
 * </p>
 */
public class GhostRenderer {

    private static final GhostRenderer INSTANCE = new GhostRenderer();

    private static final float ALPHA = 0.55f;

    // a polygon offset unit is a different world distance at each depth. A box inset behind the terrain and pulled
    // forward by the offset would trade places with the terrain pixel by pixel at some distances. A box larger than the
    // block is in front by a fixed world distance
    private static final double REMOVAL_OUTSET = 0.01D;

    private Map<BlockVector, BaseBlock> pending;

    private IVertexArrayObject additionMesh;
    private IVertexArrayObject removalMesh;
    private boolean needsRebuild;
    private boolean sawRemovals;

    // the meshes are baked relative to this block. A vertex coordinate is a float, and a world coordinate can be in the
    // millions
    private int originX, originY, originZ;

    // with false, the ghost shows through the terrain
    private boolean depthTest = true;

    private GhostRenderer() {}

    public static GhostRenderer instance() {
        return INSTANCE;
    }

    /** Show the changes. The renderer uses the map and not a copy, so do not change the map after the call. */
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

    public boolean hasPreview() {
        return pending != null && !pending.isEmpty();
    }

    /**
     * Bake the changes into VBOs, on the render thread. The {@code ISBRH} of a mod is not thread-safe, so a bake on a
     * worker thread must use the {@code AngelicaBlockSafetyRegistry} of Angelica.
     */
    private void rebuild(Minecraft mc) {
        deleteMeshes();

        Entity view = mc.renderViewEntity;
        originX = (int) Math.floor(view.posX);
        originY = (int) Math.floor(view.posY);
        originZ = (int) Math.floor(view.posZ);

        PreviewWorld world = new PreviewWorld(mc.theWorld, pending);
        sawRemovals = false;
        additionMesh = bakeAdditions(world);
        removalMesh = sawRemovals ? bakeRemovals(mc) : null;
    }

    /**
     * Bake the placed blocks with the block models into a VBO, and set {@code sawRemovals} when a change is air. A
     * block with an opaque change on each side is skipped. Return {@code null} when no quad was drawn.
     */
    private IVertexArrayObject bakeAdditions(PreviewWorld world) {
        RenderBlocks renderBlocks = new RenderBlocks(world);
        // ambient occlusion reads the light of the neighbours through the preview world, and adds noise to a
        // translucent ghost
        renderBlocks.enableAO = false;

        final DirectTessellator tes = TessellatorManager.startCapturingDirect();
        int quads = 0;
        boolean baked = false;
        try {
            tes.startDrawingQuads();
            tes.setTranslation(-originX, -originY, -originZ);

            for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
                if (entry.getValue()
                    .getType() == BlockID.AIR) {
                    sawRemovals = true;
                    continue;
                }

                Block block = Block.getBlockById(
                    entry.getValue()
                        .getType());
                if (block == null || block.getMaterial() == Material.air) continue;

                BlockVector pos = entry.getKey();
                int x = pos.getBlockX(), y = pos.getBlockY(), z = pos.getBlockZ();
                if (world.isHiddenByPendingNeighbours(x, y, z)) continue;

                if (renderBlocks.renderBlockByRenderType(block, x, y, z)) {
                    quads++;
                }
            }

            tes.setTranslation(0, 0, 0);
            baked = true;
        } finally {
            // the capture stack is thread-local state of every mod that tessellates. Pop the capture on a throw
            if (!baked) TessellatorManager.stopCapturingDirect();
        }

        return finishCapture(quads);
    }

    /** Bake a red box over each block that the stroke removes. A position with air in the world gets no box. */
    private IVertexArrayObject bakeRemovals(Minecraft mc) {
        final DirectTessellator tes = TessellatorManager.startCapturingDirect();
        int boxes = 0;
        boolean baked = false;
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

                addBox(tes, pos);
                boxes++;
            }

            tes.setTranslation(0, 0, 0);
            baked = true;
        } finally {
            if (!baked) TessellatorManager.stopCapturingDirect();
        }

        return finishCapture(boxes);
    }

    /**
     * Close a capture, and return {@code null} when nothing was drawn. A VBO from an empty capture has no vertex
     * format, and throws in the VAO code when bound. The addition pass of a brush that only removes is empty.
     */
    private static IVertexArrayObject finishCapture(int drawn) {
        if (drawn == 0) {
            TessellatorManager.stopCapturingDirect();
            return null;
        }
        return DirectTessellator.stopCapturingToVBO(VertexBufferType.IMMUTABLE);
    }

    /** Add the six faces of a red box, {@link #REMOVAL_OUTSET} larger than the block on each side. */
    private static void addBox(Tessellator tes, BlockVector block) {
        final double x0 = block.getBlockX() - REMOVAL_OUTSET;
        final double y0 = block.getBlockY() - REMOVAL_OUTSET;
        final double z0 = block.getBlockZ() - REMOVAL_OUTSET;
        final double x1 = block.getBlockX() + 1 + REMOVAL_OUTSET;
        final double y1 = block.getBlockY() + 1 + REMOVAL_OUTSET;
        final double z1 = block.getBlockZ() + 1 + REMOVAL_OUTSET;

        tes.setColorRGBA_F(1.0f, 0.25f, 0.25f, 1.0f);

        // bottom and top
        tes.addVertex(x0, y0, z0);
        tes.addVertex(x1, y0, z0);
        tes.addVertex(x1, y0, z1);
        tes.addVertex(x0, y0, z1);

        tes.addVertex(x0, y1, z1);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x0, y1, z0);

        // north and south
        tes.addVertex(x0, y0, z0);
        tes.addVertex(x0, y1, z0);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x1, y0, z0);

        tes.addVertex(x1, y0, z1);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x0, y1, z1);
        tes.addVertex(x0, y0, z1);

        // west and east
        tes.addVertex(x0, y0, z1);
        tes.addVertex(x0, y1, z1);
        tes.addVertex(x0, y1, z0);
        tes.addVertex(x0, y0, z0);

        tes.addVertex(x1, y0, z0);
        tes.addVertex(x1, y1, z0);
        tes.addVertex(x1, y1, z1);
        tes.addVertex(x1, y0, z1);
    }

    /**
     * Render the meshes translucent, with no depth write, offset toward the camera, and with no depth test when
     * {@code depthTest} is false. The GL state is restored after the draw.
     */
    private void draw(Minecraft mc, float partialTicks) {
        Entity view = mc.renderViewEntity;
        double viewX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double viewY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double viewZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            // the meshes are relative to the bake origin. Move the meshes by the camera motion since the bake
            GL11.glTranslated(originX - viewX, originY - viewY, originZ - viewZ);

            // RenderBlocks writes opaque vertex colours with setColorOpaque_F(), so with SRC_ALPHA blending the ghost
            // is solid. Blend with a constant alpha
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(1f, 1f, 1f, ALPHA);
            GL11.glBlendFunc(GL11.GL_CONSTANT_ALPHA, GL11.GL_ONE_MINUS_CONSTANT_ALPHA);

            // the quads are translucent and not sorted by depth. Draw both sides and write no depth
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);

            // a ghost face is on the face of the real block, and two faces at one depth flicker. Move the ghost toward
            // the camera in depth only. A larger mesh would leave seams where two ghost blocks meet
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
                // a removal box has a vertex colour and no texture coordinates
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
