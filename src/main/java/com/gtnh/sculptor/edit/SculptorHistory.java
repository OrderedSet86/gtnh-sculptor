package com.gtnh.sculptor.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.world.World;

/**
 * The undo history of brush strokes for each player, on the server, apart from the history of WorldEdit.
 *
 * <p>
 * A commit is not recorded with {@code LocalSession.remember()}. So {@code //undo} reverts only WorldEdit commands, and
 * {@code /scu} reverts only brush strokes. The history is keyed by player UUID, so the history of a player stays after
 * a reconnect. The history is not saved when the server stops.
 * </p>
 */
public final class SculptorHistory {

    // the default of WorldEdit. Each entry has the blocks that the stroke replaced, so a deep history uses much memory
    private static final int MAX_HISTORY = 15;

    // one history per player for the life of the server. Access is synchronized on the class
    private static final Map<UUID, SculptorHistory> BY_PLAYER = new HashMap<>();

    private final List<EditSession> history = new ArrayList<>();

    // the entries below `pointer` can be undone, and the entries from `pointer` up can be redone
    private int pointer;

    private SculptorHistory() {}

    public static synchronized SculptorHistory forPlayer(UUID playerId) {
        SculptorHistory found = BY_PLAYER.get(playerId);
        if (found == null) {
            found = new SculptorHistory();
            BY_PLAYER.put(playerId, found);
        }
        return found;
    }

    public static synchronized void forget(UUID playerId) {
        BY_PLAYER.remove(playerId);
    }

    /** Record a committed stroke, and drop the strokes that were undone. */
    public synchronized void remember(EditSession session) {
        if (session == null || session.size() == 0) return;

        while (history.size() > pointer) {
            history.remove(history.size() - 1);
        }
        history.add(session);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        pointer = history.size();
    }

    /** Undo the last stroke, and return {@code false} when there is none. */
    public synchronized boolean undo(Player player) {
        if (pointer <= 0) return false;

        pointer--;
        EditSession stored = history.get(pointer);
        stored.undo(scratchSession(stored.getWorld(), player));
        return true;
    }

    /** Redo the last stroke undone, and return {@code false} when there is none. */
    public synchronized boolean redo(Player player) {
        if (pointer >= history.size()) return false;

        EditSession stored = history.get(pointer);
        stored.redo(scratchSession(stored.getWorld(), player));
        pointer++;
        return true;
    }

    public synchronized int undoableCount() {
        return pointer;
    }

    // `EditSession.undo()` takes the reversal from the change set of the stored session, and writes the reversal
    // through the argument session. The argument session has no LocalSession
    private static EditSession scratchSession(World world, Player player) {
        EditSession scratch = WorldEdit.getInstance()
            .getEditSessionFactory()
            .getEditSession(world, -1, player);
        scratch.enableQueue();
        return scratch;
    }

}
