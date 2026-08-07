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

package com.gtnh.sculptor.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * Saving and loading camera paths.
 */
public class CameraPathStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private CameraPathStore store() {
        return new CameraPathStore(new File(folder.getRoot(), "paths"));
    }

    private static List<CameraKeyframe> samplePath() {
        return Arrays.asList(
            new CameraKeyframe(1.5D, 70.25D, -3.75D, 90.0F, -12.5F, PathSpeed.SLOW),
            new CameraKeyframe(40.0D, 88.0D, 12.0D, -170.0F, 5.0F, PathSpeed.FAST));
    }

    @Test
    public void savedPathComesBackUnchanged() throws Exception {
        CameraPathStore store = store();
        store.save("shot-1", samplePath());

        List<CameraKeyframe> loaded = store.load("shot-1");
        List<CameraKeyframe> original = samplePath();

        assertEquals("keyframe count", original.size(), loaded.size());
        for (int i = 0; i < original.size(); i++) {
            CameraKeyframe a = original.get(i);
            CameraKeyframe b = loaded.get(i);
            assertEquals("x", a.x, b.x, 0.0D);
            assertEquals("y", a.y, b.y, 0.0D);
            assertEquals("z", a.z, b.z, 0.0D);
            assertEquals("yaw", a.yaw, b.yaw, 0.0F);
            assertEquals("pitch", a.pitch, b.pitch, 0.0F);
            assertEquals("speed", a.speed, b.speed);
        }
    }

    @Test
    public void savingCreatesTheDirectoryAndListsTheName() throws Exception {
        CameraPathStore store = store();
        assertTrue(
            "nothing saved yet",
            store.list()
                .isEmpty());

        store.save("shot-1", samplePath());
        store.save("another", samplePath());

        assertEquals(Arrays.asList("another", "shot-1"), store.list());
        assertTrue(store.exists("shot-1"));

        assertTrue(store.delete("shot-1"));
        assertFalse(store.exists("shot-1"));
        assertEquals(Arrays.asList("another"), store.list());
    }

    /**
     * Speeds are written by name so a path keeps its pacing if the enum is ever reordered. A tier
     * that no longer exists costs one segment's pace, not the whole shot.
     */
    @Test
    public void anUnknownSpeedTierLoadsAsNormal() throws Exception {
        File directory = new File(folder.getRoot(), "paths");
        assertTrue(directory.mkdirs());

        NBTTagCompound frame = new NBTTagCompound();
        frame.setDouble("x", 0.0D);
        frame.setDouble("y", 64.0D);
        frame.setDouble("z", 0.0D);
        frame.setFloat("yaw", 0.0F);
        frame.setFloat("pitch", 0.0F);
        frame.setString("speed", "GLACIAL");

        NBTTagList frames = new NBTTagList();
        frames.appendTag(frame);

        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", CameraPathStore.FORMAT_VERSION);
        root.setTag("keyframes", frames);
        CompressedStreamTools.write(root, new File(directory, "odd.dat"));

        List<CameraKeyframe> loaded = store().load("odd");
        assertEquals(1, loaded.size());
        assertEquals(PathSpeed.NORMAL, loaded.get(0).speed);
    }

    /**
     * A name becomes a file name, so anything that could climb out of the directory has to be
     * refused outright rather than stripped -- stripping would quietly map two names onto one file.
     */
    @Test
    public void namesThatCouldEscapeTheDirectoryAreRefused() {
        assertFalse(CameraPathStore.isValidName("../evil"));
        assertFalse(CameraPathStore.isValidName("sub/dir"));
        assertFalse(CameraPathStore.isValidName("with space"));
        assertFalse(CameraPathStore.isValidName(""));
        assertFalse(CameraPathStore.isValidName(null));
        assertTrue(CameraPathStore.isValidName("shot-1_take2"));

        try {
            store().save("../evil", samplePath());
            fail("a name that escapes the directory must not be saved");
        } catch (IllegalArgumentException expected) {
            // exactly right
        } catch (Exception e) {
            fail("expected the name to be rejected, got " + e);
        }
    }

    @Test
    public void loadingSomethingThatWasNeverSavedFails() {
        try {
            store().load("missing");
            fail("loading a path that does not exist should fail");
        } catch (Exception expected) {
            assertTrue(
                expected.getMessage()
                    .contains("missing"));
        }
    }
}
