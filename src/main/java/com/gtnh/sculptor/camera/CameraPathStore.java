package com.gtnh.sculptor.camera;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * Save and load camera paths as files in one directory, {@code config/sculptor/paths} in the game.
 *
 * <p>
 * A file is uncompressed NBT, which any NBT editor opens:
 * </p>
 *
 * <pre>
 *     version: 1
 *     keyframes: [ { x: 1.5d, y: 70.25d, z: -3.75d, yaw: 90.0f, pitch: -12.5f, speed: "SLOW" }, ... ]
 * </pre>
 *
 * <p>
 * A speed tier is stored by {@link PathSpeed#name()}. A reorder of the tiers in the enum then changes no saved path.
 * </p>
 */
public final class CameraPathStore {

    public static final List<String> SUBCOMMANDS = Collections
        .unmodifiableList(Arrays.asList("save", "load", "list", "delete"));
    public static final int FORMAT_VERSION = 1;
    private static final String EXTENSION = ".dat";
    private static final int MAX_NAME_LENGTH = 64;
    private static final Logger LOG = SculptorMod.LOG;

    // created on the first call to instance(). SculptorMod gets the config directory from Forge in pre-init
    private static CameraPathStore instance;

    private final Path directory;

    public CameraPathStore(Path directory) {
        this.directory = directory;
    }

    public static CameraPathStore instance() {
        if (instance == null) {
            final Path config = SculptorMod.getConfigDirectory();
            final Path root = (config != null) ? config : Paths.get("config");
            instance = new CameraPathStore(
                root.resolve("sculptor")
                    .resolve("paths"));
        }
        return instance;
    }

    /** Saved path names, sorted. */
    public List<String> listNames() throws CameraPathException {
        final List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) return names;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                final String fileName = file.getFileName()
                    .toString();
                final String name = fileName.substring(0, fileName.length() - EXTENSION.length());
                if (isValidName(name)) names.add(name);
            }
        } catch (IOException exception) {
            throw rejectIo(exception, "sculptor.path.error.list", directory);
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Accept a name of 1 to 64 letters, digits, {@code _} and {@code -}. A name becomes a file name. Reject a name that
     * could leave the directory, such as {@code ../evil}. Sanitising the name would map two names to one file.
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
            if (!allowed) return false;
        }
        return true;
    }

    public boolean exists(String name) {
        return Files.isRegularFile(resolvePathFile(name));
    }

    /** Delete the saved path, and return {@code false} when no path of that name exists. */
    public boolean delete(String name) throws CameraPathException {
        try {
            return Files.deleteIfExists(resolvePathFile(name));
        } catch (IOException exception) {
            throw rejectIo(exception, "sculptor.path.error.delete", name);
        }
    }

    /** Write the path to {@code <name>.dat}, over a file of the same name, and create the directory when missing. */
    public void save(String name, List<CameraKeyframe> path) throws CameraPathException {
        final Path file = resolvePathFile(name);

        final NBTTagList frames = new NBTTagList();
        for (CameraKeyframe frame : path) {
            final NBTTagCompound tag = new NBTTagCompound();
            final CameraPose pose = frame.pose();
            tag.setDouble("x", pose.x());
            tag.setDouble("y", pose.y());
            tag.setDouble("z", pose.z());
            tag.setFloat("yaw", pose.yaw());
            tag.setFloat("pitch", pose.pitch());
            tag.setString(
                "speed",
                frame.speed()
                    .name());
            frames.appendTag(tag);
        }

        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", FORMAT_VERSION);
        root.setTag("keyframes", frames);

        try {
            Files.createDirectories(directory);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
                CompressedStreamTools.write(root, out);
            }
        } catch (IOException exception) {
            throw rejectIo(exception, "sculptor.path.error.save", name);
        }
    }

    /**
     * Read the path from {@code <name>.dat}.
     *
     * @throws CameraPathException when the file is missing, unreadable, or from a newer format version
     */
    public List<CameraKeyframe> load(String name) throws CameraPathException {
        final Path file = resolvePathFile(name);
        if (!Files.isRegularFile(file)) throw new CameraPathException("sculptor.path.missing", name);

        final NBTTagCompound root;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            root = CompressedStreamTools.read(in);
        } catch (IOException exception) {
            throw rejectIo(exception, "sculptor.path.error.load", name);
        }

        if (root.getInteger("version") > FORMAT_VERSION) {
            throw new CameraPathException("sculptor.path.error.newer", name);
        }

        final int compoundTagType = 10;
        final NBTTagList frames = root.getTagList("keyframes", compoundTagType);
        final List<CameraKeyframe> path = new ArrayList<>(frames.tagCount());
        for (int i = 0; i < frames.tagCount(); i++) {
            final NBTTagCompound tag = frames.getCompoundTagAt(i);
            path.add(
                new CameraKeyframe(
                    new CameraPose(
                        tag.getDouble("x"),
                        tag.getDouble("y"),
                        tag.getDouble("z"),
                        tag.getFloat("yaw"),
                        tag.getFloat("pitch")),
                    parseSpeed(tag.getString("speed"))));
        }
        return path;
    }

    private Path resolvePathFile(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                "bad camera path name '" + name + "' (expected 1 to 64 letters, digits, _ or -, such as shot-1)");
        }
        return directory.resolve(name + EXTENSION);
    }

    private static CameraPathException rejectIo(IOException exception, String langKey, Object subject) {
        LOG.warn("path.io_failed key={} subject={}", langKey, subject, exception);
        return new CameraPathException(exception, langKey, subject, exception.getMessage());
    }

    /** A tier name that no {@link PathSpeed} has loads as {@link PathSpeed#NORMAL}, so the rest of the path loads. */
    private static PathSpeed parseSpeed(String rawSpeed) {
        for (PathSpeed tier : PathSpeed.values()) {
            if (tier.name()
                .equals(rawSpeed)) {
                return tier;
            }
        }
        return PathSpeed.NORMAL;
    }
}
