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

package com.gtnh.sculptor.edit;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.world.World;

/**
 * Sculptor's own undo stack, per player, entirely separate from WorldEdit's.
 *
 * <p>
 * Brush commits deliberately do not go through {@code LocalSession.remember}. A player using
 * both tools has two independent trails: {@code //undo} walks back their WorldEdit commands and
 * never touches a brush stroke, and Sculptor's undo walks back brush strokes and never disturbs
 * their selection work. Sharing one stack would mean an idle brush stroke silently consuming an
 * undo slot they were saving for a {@code //set}.
 * </p>
 *
 * <p>
 * Server-side state, keyed by UUID rather than by player object so a reconnect does not orphan
 * the history. Not persisted -- it lives as long as the server does, matching how WorldEdit
 * treats its own history.
 * </p>
 */
public final class SculptorHistory {

    /**
     * Kept close to WorldEdit's default of 15. Each entry retains the blocks it replaced, so a
     * deep stack of large strokes is real memory.
     */
    private static final int MAX_HISTORY = 15;

    private static final Map<UUID, SculptorHistory> BY_PLAYER = new HashMap<UUID, SculptorHistory>();

    private final LinkedList<EditSession> history = new LinkedList<EditSession>();

    /** Index of the next slot to write; everything below it is undoable. */
    private int pointer = 0;

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

    /**
     * Record a completed edit.
     *
     * <p>
     * A new edit discards the redo tail, the same way every editor behaves: once you branch,
     * the future you abandoned is gone.
     * </p>
     */
    public synchronized void remember(EditSession session) {
        if (session == null || session.size() == 0) return;

        while (history.size() > pointer) {
            history.removeLast();
        }
        history.add(session);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        pointer = history.size();
    }

    /**
     * Undo the most recent edit.
     *
     * @return true if something was undone
     */
    public synchronized boolean undo(Player player) {
        if (pointer <= 0) return false;

        pointer--;
        EditSession stored = history.get(pointer);
        stored.undo(scratchSession(stored.getWorld(), player));
        return true;
    }

    /**
     * Redo the edit most recently undone.
     *
     * @return true if something was redone
     */
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

    /**
     * A throwaway session for the reversal to be written through.
     *
     * <p>
     * {@code EditSession.undo} only needs somewhere to write; it takes the reversal from the
     * stored session's own change set. Nothing about {@code LocalSession} is involved, which is
     * what lets this history stand on its own.
     * </p>
     */
    private static EditSession scratchSession(World world, Player player) {
        EditSession scratch = WorldEdit.getInstance()
            .getEditSessionFactory()
            .getEditSession(world, -1, player);
        scratch.enableQueue();
        return scratch;
    }

}
