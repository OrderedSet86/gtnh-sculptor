package com.gtnh.sculptor.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/** Save, load, list and delete camera paths in a real temporary directory. */
public class CameraPathStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savedPathLoadsUnchanged() throws Exception {
        final CameraPathStore store = createStore();
        store.save("shot-1", createSamplePath());

        assertEquals(createSamplePath(), store.load("shot-1"));
    }

    /** 50 generated paths of 0 to 20 keyframes, with a constant seed, so a failing path is the same on the next run. */
    @Test
    public void generatedPathsLoadUnchanged() throws Exception {
        final Random random = new Random(20261114L);
        final CameraPathStore store = createStore();
        final PathSpeed[] tiers = PathSpeed.values();

        for (int run = 0; run < 50; run++) {
            final List<CameraKeyframe> path = new ArrayList<>();
            final int size = random.nextInt(21);
            for (int i = 0; i < size; i++) {
                path.add(
                    new CameraKeyframe(
                        new CameraPose(
                            (random.nextDouble() - 0.5D) * 6.0e7D,
                            random.nextDouble() * 256.0D,
                            (random.nextDouble() - 0.5D) * 6.0e7D,
                            (random.nextFloat() - 0.5F) * 720.0F,
                            (random.nextFloat() - 0.5F) * 180.0F),
                        tiers[random.nextInt(tiers.length)]));
            }
            store.save("run-" + run, path);
            assertEquals("path " + run, path, store.load("run-" + run));
        }
    }

    @Test
    public void saveCreatesTheDirectoryAndListsTheName() throws Exception {
        final CameraPathStore store = createStore();
        assertTrue(
            "nothing saved yet",
            store.listNames()
                .isEmpty());

        store.save("shot-1", createSamplePath());
        store.save("another", createSamplePath());

        assertEquals(Arrays.asList("another", "shot-1"), store.listNames());
        assertTrue(store.exists("shot-1"));

        assertTrue(store.delete("shot-1"));
        assertFalse(store.exists("shot-1"));
        assertFalse("a second delete finds nothing", store.delete("shot-1"));
        assertEquals(Arrays.asList("another"), store.listNames());
    }

    @Test
    public void unknownSpeedTierLoadsAsNormal() throws Exception {
        final Path directory = folder.getRoot()
            .toPath()
            .resolve("paths");
        Files.createDirectories(directory);

        final NBTTagCompound frame = new NBTTagCompound();
        frame.setDouble("x", 0.0D);
        frame.setDouble("y", 64.0D);
        frame.setDouble("z", 0.0D);
        frame.setFloat("yaw", 0.0F);
        frame.setFloat("pitch", 0.0F);
        frame.setString("speed", "GLACIAL");

        final NBTTagList frames = new NBTTagList();
        frames.appendTag(frame);

        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", CameraPathStore.FORMAT_VERSION);
        root.setTag("keyframes", frames);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(directory.resolve("odd.dat")))) {
            CompressedStreamTools.write(root, out);
        }

        final List<CameraKeyframe> loaded = createStore().load("odd");
        assertEquals(1, loaded.size());
        assertEquals(
            PathSpeed.NORMAL,
            loaded.get(0)
                .speed());
    }

    @Test
    public void nameThatCouldLeaveTheDirectoryIsRejected() throws Exception {
        assertFalse(CameraPathStore.isValidName("../evil"));
        assertFalse(CameraPathStore.isValidName("sub/dir"));
        assertFalse(CameraPathStore.isValidName("with space"));
        assertFalse(CameraPathStore.isValidName(""));
        assertFalse(CameraPathStore.isValidName(null));
        assertFalse(CameraPathStore.isValidName(repeat('a', 65)));
        assertTrue(CameraPathStore.isValidName(repeat('a', 64)));
        assertTrue(CameraPathStore.isValidName("shot-1_take2"));

        try {
            createStore().save("../evil", createSamplePath());
            fail("a name that leaves the directory must not be saved");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("../evil"));
        }
    }

    @Test
    public void loadOfAPathNeverSavedFailsWithTheName() {
        try {
            createStore().load("missing");
            fail("a load of a path that does not exist must fail");
        } catch (CameraPathException expected) {
            assertEquals("sculptor.path.missing", expected.getLangKey());
            assertEquals(Arrays.asList("missing"), Arrays.asList(expected.getFormatValues()));
        }
    }

    private CameraPathStore createStore() {
        return new CameraPathStore(
            folder.getRoot()
                .toPath()
                .resolve("paths"));
    }

    private static List<CameraKeyframe> createSamplePath() {
        return Arrays.asList(
            new CameraKeyframe(new CameraPose(1.5D, 70.25D, -3.75D, 90.0F, -12.5F), PathSpeed.SLOW),
            new CameraKeyframe(new CameraPose(40.0D, 88.0D, 12.0D, -170.0F, 5.0F), PathSpeed.FAST));
    }

    private static String repeat(char c, int count) {
        final char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
