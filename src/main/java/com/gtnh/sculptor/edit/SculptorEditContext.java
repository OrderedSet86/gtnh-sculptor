package com.gtnh.sculptor.edit;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.World;

/**
 * The only way a Sculptor brush reads and writes blocks.
 *
 * <p>
 * A preview context writes to a {@link PreviewBuffer} and never changes the world. A commit context writes through an
 * {@link EditSession}. A brush runs the same code against both, so the ghost and the committed result come from one
 * execution.
 * </p>
 *
 * <p>
 * {@code EditSession.getBlock()}, {@code getBlockType()} and {@code getBlockData()} read the world directly, past the
 * extent chain of the session. A capture in the extent chain is therefore invisible to those reads. Every read of a
 * brush goes through {@link #getBlock}, which reads the buffer first.
 * </p>
 */
public class SculptorEditContext {

    private static final BaseBlock AIR = new BaseBlock(BlockID.AIR);

    // exactly one of `session` and `buffer` is set
    private final EditSession session;
    private final World world;
    private final PreviewBuffer buffer;

    private PlacementMask placementMask = PlacementMask.OVERWRITE;
    private BaseBlock material = AIR;

    // null until trackWrites()
    private Set<BlockVector> written;
    private boolean guardRemovals;

    private int changeCount;

    private SculptorEditContext(EditSession session, World world, PreviewBuffer buffer) {
        this.session = session;
        this.world = world;
        this.buffer = buffer;
    }

    /** A context that writes to a {@link PreviewBuffer}, and never to the world. */
    public static SculptorEditContext preview(World world) {
        return new SculptorEditContext(null, world, new PreviewBuffer());
    }

    /** A context that writes through the session to the world. */
    public static SculptorEditContext commit(EditSession session) {
        return new SculptorEditContext(session, session.getWorld(), null);
    }

    public boolean isPreview() {
        return buffer != null;
    }

    public World getWorld() {
        return world;
    }

    public void setPlacementMask(PlacementMask mask) {
        this.placementMask = (mask != null) ? mask : PlacementMask.OVERWRITE;
    }

    public PlacementMask getPlacementMask() {
        return placementMask;
    }

    /**
     * Record each position that this context writes from now on. Under {@link #setGuardRemovals}, a removal at a
     * recorded position is allowed: the block is material of the stroke, not terrain. The set has one entry per written
     * block, which at the maximum radius of the server is millions of entries, so recording is off until this call.
     */
    public void trackWrites() {
        if (written == null) written = new HashSet<BlockVector>();
    }

    /**
     * Apply the placement mask to a removal too, except at a position that {@link #trackWrites} recorded.
     *
     * <p>
     * Without the guard, the mask applies only to a placement. A mask on removals would stop every carving brush under
     * {@link PlacementMask#AIR_ONLY}, because a removal writes air over a block that is not air. The auto-smooth pass
     * turns the guard on: the player did not choose that pass, and under "air only" the pass must not cut into the
     * terrain. Call {@link #trackWrites} at the start of the stroke, so the pass can still remove the material of the
     * stroke.
     * </p>
     */
    public void setGuardRemovals(boolean guard) {
        this.guardRemovals = guard;
    }

    /** Set the brush material, which {@link PlacementMask#REPLACE_MATERIAL} compares against. */
    public void setMaterial(BaseBlock material) {
        this.material = (material != null) ? material : AIR;
    }

    public boolean setAir(int x, int y, int z) throws MaxChangedBlocksException {
        return setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
    }

    /** Write a block, and return {@code false} when the placement mask rejects the write. */
    public boolean setBlock(Vector position, BaseBlock block) throws MaxChangedBlocksException {
        // the mask applies to a placement, and to a removal only under setGuardRemovals()
        boolean placing = block.getType() != BlockID.AIR;

        // toBlockVector() allocates, and a stroke writes tens of thousands of blocks. Convert only when recording
        BlockVector key = (written != null) ? position.toBlockVector() : null;
        boolean ours = key != null && written.contains(key);

        // check inspectsExisting() first. Under OVERWRITE the check skips the read of the existing block
        if ((placing || (guardRemovals && !ours)) && placementMask.inspectsExisting()
            && !placementMask.allows(getBlock(position), material)) {
            return false;
        }

        if (buffer != null) {
            buffer.put(position, block);
        } else {
            session.setBlock(position, block);
        }
        if (key != null) written.add(key);
        changeCount++;
        return true;
    }

    public boolean isAir(int x, int y, int z) {
        return isAir(new Vector(x, y, z));
    }

    public boolean isAir(Vector position) {
        return getBlock(position).getType() == BlockID.AIR;
    }

    /** Read a block, with the writes of this context applied. A position with no block reads as air. */
    public BaseBlock getBlock(Vector position) {
        if (buffer != null) {
            BaseBlock pending = buffer.get(position);
            if (pending != null) return pending;
        }
        BaseBlock block = (session != null) ? session.getBlock(position) : world.getBlock(position);
        return (block != null) ? block : AIR;
    }

    /**
     * The writes of a preview context, in the order of the first write to each position, and an empty map for a commit
     * context.
     */
    public Map<BlockVector, BaseBlock> getPendingChanges() {
        return (buffer != null) ? buffer.asMap() : Collections.<BlockVector, BaseBlock>emptyMap();
    }

    /** The number of writes that the placement mask allowed. */
    public int getChangeCount() {
        return changeCount;
    }

}
