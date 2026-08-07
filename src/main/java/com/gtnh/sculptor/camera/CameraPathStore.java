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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * Reads and writes camera paths as files.
 *
 * <p>
 * A path is a shot, and a shot is worth keeping -- it takes real time to frame and until now it
 * lived in one field on one client and was gone at the next restart, or the next press of Del.
 * </p>
 *
 * <p>
 * Stored as uncompressed NBT, which is Minecraft's own format and needs no dependency, and can be
 * opened by any of the NBT editors people already have. Speed tiers are written as
 * {@link PathSpeed#name()} rather than as ordinals -- the wire codec in {@code SculptorNetwork}
 * can use ordinals because both ends of a packet are the same build, but a file outlives the
 * enum's declaration order, and a path that silently changes pace after a reorder would be a
 * miserable thing to debug.
 * </p>
 *
 * <p>
 * Deliberately free of Minecraft's client classes so it can be tested headlessly: it takes the
 * directory to work in rather than looking one up.
 * </p>
 */
public final class CameraPathStore {

    static final int FORMAT_VERSION = 1;

    private static final String EXTENSION = ".dat";

    private static CameraPathStore instance;

    private final File directory;

    public CameraPathStore(File directory) {
        this.directory = directory;
    }

    /** The store under the Forge config directory, {@code config/sculptor/paths}. */
    public static CameraPathStore instance() {
        if (instance == null) {
            File config = SculptorMod.configDirectory();
            File root = (config != null) ? config : new File("config");
            instance = new CameraPathStore(new File(new File(root, "sculptor"), "paths"));
        }
        return instance;
    }

    /**
     * Names are used to build a file name, so anything that could climb out of the directory --
     * a separator, a {@code ..}, a drive letter -- has to be refused rather than sanitised away,
     * or two different names quietly become the same file.
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private File fileFor(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("path names may only contain letters, digits, _ and -");
        }
        return new File(directory, name + EXTENSION);
    }

    /** Saved path names, sorted. Empty if nothing has been saved yet. */
    public List<String> list() {
        String[] files = directory.list();
        if (files == null) return Collections.emptyList();

        List<String> names = new ArrayList<String>();
        for (String file : files) {
            if (!file.endsWith(EXTENSION)) continue;
            String name = file.substring(0, file.length() - EXTENSION.length());
            if (isValidName(name)) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    public boolean exists(String name) {
        return fileFor(name).isFile();
    }

    public boolean delete(String name) {
        return fileFor(name).delete();
    }

    public void save(String name, List<CameraKeyframe> path) throws IOException {
        File file = fileFor(name);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }

        NBTTagList frames = new NBTTagList();
        for (CameraKeyframe frame : path) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setDouble("x", frame.x);
            tag.setDouble("y", frame.y);
            tag.setDouble("z", frame.z);
            tag.setFloat("yaw", frame.yaw);
            tag.setFloat("pitch", frame.pitch);
            tag.setString("speed", frame.speed.name());
            frames.appendTag(tag);
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", FORMAT_VERSION);
        root.setTag("keyframes", frames);

        CompressedStreamTools.write(root, file);
    }

    public List<CameraKeyframe> load(String name) throws IOException {
        File file = fileFor(name);
        if (!file.isFile()) throw new IOException("no saved path called '" + name + "'");

        NBTTagCompound root = CompressedStreamTools.read(file);
        if (root == null) throw new IOException("'" + name + "' is empty");

        int version = root.getInteger("version");
        if (version > FORMAT_VERSION) {
            throw new IOException("'" + name + "' was saved by a newer version of Sculptor");
        }

        NBTTagList frames = root.getTagList("keyframes", 10);
        List<CameraKeyframe> path = new ArrayList<CameraKeyframe>(frames.tagCount());
        for (int i = 0; i < frames.tagCount(); i++) {
            NBTTagCompound tag = frames.getCompoundTagAt(i);
            path.add(
                new CameraKeyframe(
                    tag.getDouble("x"),
                    tag.getDouble("y"),
                    tag.getDouble("z"),
                    tag.getFloat("yaw"),
                    tag.getFloat("pitch"),
                    speedNamed(tag.getString("speed"))));
        }
        return path;
    }

    /**
     * A tier that no longer exists loads as {@code NORMAL} rather than failing the whole path.
     * Losing the pace of one segment is recoverable; losing the shot is not.
     */
    private static PathSpeed speedNamed(String name) {
        for (PathSpeed tier : PathSpeed.values()) {
            if (tier.name()
                .equals(name)) {
                return tier;
            }
        }
        return PathSpeed.NORMAL;
    }

    /** For the command's usage text. */
    public static List<String> subcommands() {
        return Arrays.asList("save", "load", "list", "delete");
    }
}
