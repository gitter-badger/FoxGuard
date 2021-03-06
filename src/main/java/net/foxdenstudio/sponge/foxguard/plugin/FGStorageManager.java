/*
 * This file is part of FoxGuard, licensed under the MIT License (MIT).
 *
 * Copyright (c) gravityfox - https://gravityfox.net/
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.foxdenstudio.sponge.foxguard.plugin;

import net.foxdenstudio.sponge.foxcore.plugin.util.CacheMap;
import net.foxdenstudio.sponge.foxguard.plugin.controller.IController;
import net.foxdenstudio.sponge.foxguard.plugin.handler.GlobalHandler;
import net.foxdenstudio.sponge.foxguard.plugin.handler.IHandler;
import net.foxdenstudio.sponge.foxguard.plugin.object.IFGObject;
import net.foxdenstudio.sponge.foxguard.plugin.object.factory.FGFactoryManager;
import net.foxdenstudio.sponge.foxguard.plugin.region.GlobalRegion;
import net.foxdenstudio.sponge.foxguard.plugin.region.IRegion;
import net.foxdenstudio.sponge.foxguard.plugin.region.world.GlobalWorldRegion;
import net.foxdenstudio.sponge.foxguard.plugin.region.world.IWorldRegion;
import net.foxdenstudio.sponge.foxguard.plugin.util.FGUtil;
import org.mapdb.*;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.World;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Created by Fox on 4/6/2016.
 */
public final class FGStorageManager {

    public static final String[] FS_ILLEGAL_NAMES = {"con", "prn", "aux", "nul", "com0", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"};

    public final HashMap<IFGObject, Boolean> defaultModifiedMap;
    private static FGStorageManager instance;
    private final Logger logger = FoxGuardMain.instance().getLogger();
    private final Set<LoadEntry> loaded = new HashSet<>();
    private final Path directory = getDirectory();
    private final Map<String, Path> worldDirectories;

    private FGStorageManager() {
        defaultModifiedMap = new CacheMap<>((k, m) -> {
            if (k instanceof IFGObject) {
                m.put((IFGObject) k, true);
                return true;
            } else return null;
        });
        worldDirectories = new CacheMap<>((k, m) -> {
            if (k instanceof String) {
                Path dir = getWorldDirectory((String) k);
                m.put((String) k, dir);
                return dir;
            } else return null;
        });
    }

    public static FGStorageManager getInstance() {
        if (instance == null) instance = new FGStorageManager();
        return instance;
    }

    public void saveRegions() {
        saveRegions(false);
    }

    public synchronized void saveRegions(boolean force) {
        logger.info("Saving regions" + (force ? " (forced save)" : ""));
        try (DB mainDB = DBMaker.fileDB(directory.resolve("regions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            mainMap.clear();
            linksMap.clear();

            Path dir = directory.resolve("regions");
            constructDirectory(dir);
            FGManager.getInstance().getRegions().forEach(fgObject -> {
                String name = fgObject.getName();
                if (fgObject.autoSave()) {
                    Path singleDir = dir.resolve(name.toLowerCase());
                    if (force || fgObject.shouldSave()) {
                        logger.info("Saving region \"" + name + "\" in directory: " + singleDir);
                        constructDirectory(singleDir);
                        try {
                            fgObject.save(singleDir);
                        } catch (Exception e) {
                            logger.error("There was an error while saving region \"" + name + "\"!", e);
                        }

                        logger.info("Saving metadata for region \"" + name + "\"");
                        try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                            Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                            Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                            Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                            Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                            metaName.set(name);
                            metaCategory.set(FGUtil.getCategory(fgObject));
                            metaType.set(fgObject.getUniqueTypeString());
                            metaEnabled.set(fgObject.isEnabled());
                        }
                    } else {
                        logger.info("Region \"" + name + "\" is already up to date. Skipping...");
                    }
                    mainMap.put(name, FGUtil.getCategory(fgObject));
                    typeMap.put(name, fgObject.getUniqueTypeString());
                    enabledMap.put(name, fgObject.isEnabled());

                    defaultModifiedMap.put(fgObject, false);
                } else {
                    logger.info("Region " + fgObject.getName() + " does not need saving. Skipping...");
                }
                if (fgObject.saveLinks()) {
                    linksMap.put(name, serializeHandlerList(fgObject.getHandlers()));
                } else {
                    logger.info("Region " + fgObject.getName() + " does not need its links saved. Skipping...");
                }
            });
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveRegions(force);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public void saveWorldRegions(World world) {
        saveWorldRegions(world, false);
    }

    public synchronized void saveWorldRegions(World world, boolean force) {
        logger.info("Saving world regions in world \"" + world.getName() + "\"" + (force ? " (forced save)" : ""));
        try (DB mainDB = DBMaker.fileDB(worldDirectories.get(world.getName()).resolve("wregions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            mainMap.clear();
            linksMap.clear();

            Path dir = worldDirectories.get(world.getName()).resolve("wregions");
            constructDirectory(dir);
            FGManager.getInstance().getWorldRegions(world).forEach(fgObject -> {
                String name = fgObject.getName();
                if (fgObject.autoSave()) {
                    Path singleDir = dir.resolve(name.toLowerCase());
                    if (force || fgObject.shouldSave()) {
                        logger.info("Saving world region \"" + name + "\" in directory: " + singleDir);
                        constructDirectory(singleDir);
                        try {
                            fgObject.save(singleDir);
                        } catch (Exception e) {
                            logger.error("There was an error while saving world region \"" + name + "\" in world \"" + world.getName() + "\"!", e);
                        }

                        logger.info("Saving metadata for world region \"" + name + "\"");
                        try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                            Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                            Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                            Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                            Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                            metaName.set(name);
                            metaCategory.set(FGUtil.getCategory(fgObject));
                            metaType.set(fgObject.getUniqueTypeString());
                            metaEnabled.set(fgObject.isEnabled());
                        }
                    } else {
                        logger.info("Region \"" + name + "\" is already up to date. Skipping...");
                    }

                    mainMap.put(name, FGUtil.getCategory(fgObject));
                    typeMap.put(name, fgObject.getUniqueTypeString());
                    enabledMap.put(name, fgObject.isEnabled());

                    defaultModifiedMap.put(fgObject, false);
                } else {
                    logger.info("World region " + fgObject.getName() + " does not need saving. Skipping...");
                }
                if (fgObject.saveLinks()) {
                    linksMap.put(name, serializeHandlerList(fgObject.getHandlers()));
                } else {
                    logger.info("World region " + fgObject.getName() + " does not need its links saved. Skipping...");
                }
            });
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("wregions.foxdb"));
                saveWorldRegions(world, force);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public void saveHandlers() {
        saveHandlers(false);
    }

    public synchronized void saveHandlers(boolean force) {
        logger.info("Saving handlers" + (force ? " (forced save)" : ""));
        try (DB mainDB = DBMaker.fileDB(directory.resolve("handlers.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, Integer> priorityMap = mainDB.hashMap("priority", Serializer.STRING, Serializer.INTEGER).createOrOpen();

            mainMap.clear();

            Path dir = directory.resolve("handlers");
            constructDirectory(dir);
            FGManager.getInstance().getHandlers().forEach(fgObject -> {
                if (fgObject.autoSave()) {
                    String name = fgObject.getName();
                    Path singleDir = dir.resolve(name.toLowerCase());
                    if (force || fgObject.shouldSave()) {
                        logger.info("Saving handler \"" + name + "\" in directory: " + singleDir);
                        constructDirectory(singleDir);
                        try {
                            fgObject.save(singleDir);
                        } catch (Exception e) {
                            logger.error("There was an error while saving handler \"" + name + "\"!", e);
                        }

                        logger.info("Saving metadata for handler \"" + name + "\"");
                        try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                            Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                            Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                            Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                            Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                            Atomic.Integer metaPriority = metaDB.atomicInteger("priority").createOrOpen();
                            metaName.set(name);
                            metaCategory.set(FGUtil.getCategory(fgObject));
                            metaType.set(fgObject.getUniqueTypeString());
                            metaEnabled.set(fgObject.isEnabled());
                            metaPriority.set(fgObject.getPriority());
                        }
                    } else {
                        logger.info("Region \"" + name + "\" is already up to date. Skipping...");
                    }

                    mainMap.put(name, FGUtil.getCategory(fgObject));
                    typeMap.put(name, fgObject.getUniqueTypeString());
                    enabledMap.put(name, fgObject.isEnabled());
                    priorityMap.put(name, fgObject.getPriority());

                    defaultModifiedMap.put(fgObject, false);
                } else {
                    logger.info("Handler " + fgObject.getName() + " does not need saving. Skipping...");
                }
            });
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("handlers.foxdb"));
                saveHandlers(force);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public synchronized void saveRegion(IRegion fgObject) {
        if (fgObject instanceof IWorldRegion) saveWorldRegion((IWorldRegion) fgObject);
        else try (DB mainDB = DBMaker.fileDB(directory.resolve("regions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            Path dir = directory.resolve("regions");
            constructDirectory(dir);
            String name = fgObject.getName();
            Path singleDir = dir.resolve(name.toLowerCase());
            logger.info("Saving region \"" + name + "\" in directory: " + singleDir);
            constructDirectory(singleDir);
            try {
                fgObject.save(singleDir);
            } catch (Exception e) {
                logger.error("There was an error while saving region \"" + name + "\"!", e);
            }

            logger.info("Saving metadata for region \"" + name + "\"");
            try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                metaName.set(name);
                metaCategory.set(FGUtil.getCategory(fgObject));
                metaType.set(fgObject.getUniqueTypeString());
                metaEnabled.set(fgObject.isEnabled());
            }

            mainMap.put(name, FGUtil.getCategory(fgObject));
            typeMap.put(name, fgObject.getUniqueTypeString());
            enabledMap.put(name, fgObject.isEnabled());
            linksMap.put(name, serializeHandlerList(fgObject.getHandlers()));

            defaultModifiedMap.put(fgObject, false);
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveRegions();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public synchronized void saveWorldRegion(IWorldRegion fgObject) {
        try (DB mainDB = DBMaker.fileDB(worldDirectories.get(fgObject.getWorld().getName()).resolve("wregions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            Path dir = worldDirectories.get(fgObject.getWorld().getName()).resolve("wregions");
            constructDirectory(dir);
            String name = fgObject.getName();
            Path singleDir = dir.resolve(name.toLowerCase());
            logger.info("Saving world region \"" + name + "\" in directory: " + singleDir);
            constructDirectory(singleDir);
            try {
                fgObject.save(singleDir);
            } catch (Exception e) {
                logger.error("There was an error while saving world region \"" + name + "\" in world \"" + fgObject.getWorld().getName() + "\"!", e);
            }

            logger.info("Saving metadata for world region \"" + name + "\"");
            try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                metaName.set(name);
                metaCategory.set(FGUtil.getCategory(fgObject));
                metaType.set(fgObject.getUniqueTypeString());
                metaEnabled.set(fgObject.isEnabled());
            }

            mainMap.put(name, FGUtil.getCategory(fgObject));
            typeMap.put(name, fgObject.getUniqueTypeString());
            enabledMap.put(name, fgObject.isEnabled());
            linksMap.put(name, serializeHandlerList(fgObject.getHandlers()));

            defaultModifiedMap.put(fgObject, false);
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveWorldRegions(fgObject.getWorld());
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public synchronized void saveHandler(IHandler fgObject) {
        try (DB mainDB = DBMaker.fileDB(directory.resolve("handlers.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, Integer> priorityMap = mainDB.hashMap("priority", Serializer.STRING, Serializer.INTEGER).createOrOpen();

            Path dir = directory.resolve("handlers");
            constructDirectory(dir);
            String name = fgObject.getName();
            Path singleDir = dir.resolve(name.toLowerCase());
            logger.info("Saving handler \"" + name + "\" in directory: " + singleDir);
            constructDirectory(singleDir);
            try {
                fgObject.save(singleDir);
            } catch (Exception e) {
                logger.error("There was an error while saving handler \"" + name + "\"!", e);
            }

            logger.info("Saving metadata for handler \"" + name + "\"");
            try (DB metaDB = DBMaker.fileDB(singleDir.resolve("metadata.foxdb").normalize().toString()).make()) {
                Atomic.String metaName = metaDB.atomicString("name").createOrOpen();
                Atomic.String metaCategory = metaDB.atomicString("category").createOrOpen();
                Atomic.String metaType = metaDB.atomicString("type").createOrOpen();
                Atomic.Boolean metaEnabled = metaDB.atomicBoolean("enabled").createOrOpen();
                Atomic.Integer metaPriority = metaDB.atomicInteger("priority").createOrOpen();
                metaName.set(name);
                metaCategory.set(FGUtil.getCategory(fgObject));
                metaType.set(fgObject.getUniqueTypeString());
                metaEnabled.set(fgObject.isEnabled());
                metaPriority.set(fgObject.getPriority());
            }

            mainMap.put(name, FGUtil.getCategory(fgObject));
            typeMap.put(name, fgObject.getUniqueTypeString());
            enabledMap.put(name, fgObject.isEnabled());
            priorityMap.put(name, fgObject.getPriority());

            defaultModifiedMap.put(fgObject, false);
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveHandlers();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    public synchronized void removeRegion(IRegion fgObject) {
        if (fgObject instanceof IWorldRegion) removeWorldRegion((IWorldRegion) fgObject);
        else try (DB mainDB = DBMaker.fileDB(directory.resolve("regions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            mainMap.remove(fgObject.getName());
            typeMap.remove(fgObject.getName());
            enabledMap.remove(fgObject.getName());
            linksMap.remove(fgObject.getName());
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveRegions();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        if (FGConfigManager.getInstance().cleanupFiles()) {
            Path singleDir = new LoadEntry(fgObject).getPath();
            if (Files.exists(singleDir)) {
                logger.warn("Cleaning up unused files");
                System.gc();
                System.runFinalization();
                deleteDirectory(singleDir);
            }
        }
    }

    public synchronized void removeWorldRegion(IWorldRegion fgObject) {
        try (DB mainDB = DBMaker.fileDB(worldDirectories.get(fgObject.getWorld().getName()).resolve("wregions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();

            mainMap.remove(fgObject.getName());
            typeMap.remove(fgObject.getName());
            enabledMap.remove(fgObject.getName());
            linksMap.remove(fgObject.getName());
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveWorldRegions(fgObject.getWorld());
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        if (FGConfigManager.getInstance().cleanupFiles()) {
            Path singleDir = new LoadEntry(fgObject).getPath();
            if (Files.exists(singleDir)) {
                logger.warn("Cleaning up unused files");
                System.gc();
                System.runFinalization();
                deleteDirectory(singleDir);
            }
        }
    }

    public synchronized void removeHandler(IHandler fgObject) {
        try (DB mainDB = DBMaker.fileDB(directory.resolve("handlers.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, Integer> priorityMap = mainDB.hashMap("priority", Serializer.STRING, Serializer.INTEGER).createOrOpen();

            mainMap.remove(fgObject.getName());
            typeMap.remove(fgObject.getName());
            enabledMap.remove(fgObject.getName());
            priorityMap.remove(fgObject.getName());
        } catch (DBException.DataCorruption e) {
            try {
                Files.deleteIfExists(directory.resolve("regions.foxdb"));
                saveHandlers();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        if (FGConfigManager.getInstance().cleanupFiles()) {
            Path singleDir = new LoadEntry(fgObject).getPath();
            if (Files.exists(singleDir)) {
                logger.warn("Cleaning up unused files");
                System.gc();
                System.runFinalization();
                deleteDirectory(singleDir);
            }
        }
    }

    public synchronized void loadRegions() {
        try (DB mainDB = DBMaker.fileDB(directory.resolve("regions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();

            Path dir = directory.resolve("regions");
            mainMap.entrySet().forEach((entry) -> {
                String name = entry.getKey();
                Path singleDir = dir.resolve(name.toLowerCase());
                Path metaDataFile = singleDir.resolve("metadata.foxdb");
                logger.info("Loading region \"" + name + "\" from " + singleDir);
                if (Files.exists(metaDataFile) && !Files.isDirectory(metaDataFile)) {
                    String category;
                    String type;
                    Boolean enabled;
                    try (DB metaDB = DBMaker.fileDB(metaDataFile.normalize().toString()).make()) {
                        category = metaDB.exists("category") ? metaDB.atomicString("category").createOrOpen().get() : entry.getValue();
                        type = metaDB.exists("type") ? metaDB.atomicString("type").createOrOpen().get() : typeMap.get(name);
                        enabled = metaDB.exists("enabled") ? metaDB.atomicBoolean("enabled").createOrOpen().get() : enabledMap.get(name);
                    }
                    logger.info("Region info loaded!  Name: \"" + name +
                            "\",  Category: \"" + category +
                            "\",  Type: \"" + type +
                            "\",  Enabled: " + enabled);
                    if (name.equalsIgnoreCase(GlobalRegion.NAME)) {
                        logger.info("Global region found! Skipping...");
                        return;
                    }
                    if (!FGManager.getInstance().isRegionNameAvailable(name)) {
                        logger.error("Name conflict detected! \"" + name + "\" is already in use! A world region is likely already using that name.");
                        if (FGConfigManager.getInstance().cleanupFiles()) {
                            logger.warn("Cleaning up unused files");
                            System.gc();
                            System.runFinalization();
                            deleteDirectory(singleDir);
                        }
                    }
                    if (category == null) category = "";
                    if (type == null) type = "";
                    IRegion object = null;
                    try {
                        if (category.equalsIgnoreCase("region"))
                            object = FGFactoryManager.getInstance().createRegion(singleDir, name, type, enabled);
                        else logger.warn("Category \"" + category + "\" is invalid!");
                    } catch (Exception e) {
                        logger.error("There was an error creating the region!", e);
                    }
                    if (object != null) {
                        loaded.add(new LoadEntry(object));
                        FGManager.getInstance().addRegion(object);
                        logger.info("Successfully created and added region \"" + name + "\"!");
                    } else {
                        logger.warn("A region was unable to be created. Either the metadata is incorrect, or there is no longer a factory available to create it.");
                        if (FGConfigManager.getInstance().cleanupFiles()) {
                            logger.warn("Cleaning up unused files");
                            System.gc();
                            System.runFinalization();
                            deleteDirectory(singleDir);
                        }
                    }
                } else {
                    logger.warn("Metadata file not found! Skipping...");
                    if (Files.exists(singleDir)) {
                        if (isEmptyDirectory(singleDir)) {
                            logger.warn("Empty region directory found. Deleting...");
                            try {
                                Files.delete(singleDir);
                            } catch (IOException e) {
                                logger.error("There was an error deleting the region directory: " + singleDir, e);
                            }
                        } else {
                            if (FGConfigManager.getInstance().cleanupFiles()) {
                                logger.warn("Cleaning up unused files");
                                System.gc();
                                System.runFinalization();
                                deleteDirectory(singleDir);
                            }
                        }
                    }
                }
            });
        }
    }

    public synchronized void loadWorldRegions(World world) {
        try (DB mainDB = DBMaker.fileDB(worldDirectories.get(world.getName()).resolve("wregions.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();

            Path dir = worldDirectories.get(world.getName()).resolve("wregions");
            mainMap.entrySet().forEach((entry) -> {
                String name = entry.getKey();
                Path singleDir = dir.resolve(name.toLowerCase());
                Path metaDataFile = singleDir.resolve("metadata.foxdb");
                logger.info("Loading world region \"" + name + "\" from " + singleDir);
                if (Files.exists(metaDataFile) && !Files.isDirectory(metaDataFile)) {
                    String category;
                    String type;
                    Boolean enabled;
                    try (DB metaDB = DBMaker.fileDB(metaDataFile.normalize().toString()).make()) {
                        category = metaDB.exists("category") ? metaDB.atomicString("category").createOrOpen().get() : entry.getValue();
                        type = metaDB.exists("type") ? metaDB.atomicString("type").createOrOpen().get() : typeMap.get(name);
                        enabled = metaDB.exists("enabled") ? metaDB.atomicBoolean("enabled").createOrOpen().get() : enabledMap.get(name);
                    }
                    logger.info("World region info loaded!  Name: \"" + name +
                            "\",  Category: \"" + category +
                            "\",  Type: \"" + type +
                            "\",  Enabled: " + enabled);
                    if (name.equalsIgnoreCase(GlobalWorldRegion.NAME)) {
                        logger.info("Global world region found! Skipping...");
                        return;
                    }
                    if (!FGManager.getInstance().isWorldRegionNameAvailable(name, world)) {
                        logger.error("Name conflict detected! \"" + name + "\" is already in use! A super region is likely already using that name.");
                        if (FGConfigManager.getInstance().cleanupFiles()) {
                            logger.warn("Cleaning up unused files");
                            System.gc();
                            System.runFinalization();
                            deleteDirectory(singleDir);
                        }
                    }
                    if (category == null) category = "";
                    if (type == null) type = "";
                    IWorldRegion object = null;
                    try {
                        if (category.equalsIgnoreCase("worldregion"))
                            object = FGFactoryManager.getInstance().createWorldRegion(singleDir, name, type, enabled);
                        else logger.warn("Category \"" + category + "\" is invalid!");
                    } catch (Exception e) {
                        logger.error("There was an error creating the world region!", e);
                    }
                    if (object != null) {
                        loaded.add(new LoadEntry(object, world.getName()));
                        FGManager.getInstance().addWorldRegion(world, object);
                        logger.info("Successfully created and added world region \"" + name + "\"!");
                    } else {
                        logger.warn("A world region was unable to be created. Either the metadata is incorrect, or there is no longer a factory available to create it.");
                        if (FGConfigManager.getInstance().cleanupFiles()) {
                            logger.warn("Cleaning up unused files");
                            System.gc();
                            System.runFinalization();
                            deleteDirectory(singleDir);
                        }
                    }
                } else {
                    logger.warn("Metadata file not found! Skipping...");
                    if (Files.exists(singleDir)) {
                        if (isEmptyDirectory(singleDir)) {
                            logger.warn("Empty world region directory found. Deleting...");
                            try {
                                Files.delete(singleDir);
                            } catch (IOException e) {
                                logger.error("There was an error deleting the world region directory: " + singleDir, e);
                            }
                        } else {
                            if (FGConfigManager.getInstance().cleanupFiles()) {
                                logger.warn("Cleaning up unused files");
                                System.gc();
                                System.runFinalization();
                                deleteDirectory(singleDir);
                            }
                        }
                    }
                }
            });
        }
    }

    public synchronized void loadHandlers() {
        try (DB mainDB = DBMaker.fileDB(directory.resolve("handlers.foxdb").normalize().toString()).make()) {
            Map<String, String> mainMap = mainDB.hashMap("main", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, String> typeMap = mainDB.hashMap("types", Serializer.STRING, Serializer.STRING).createOrOpen();
            Map<String, Boolean> enabledMap = mainDB.hashMap("enabled", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();
            Map<String, Integer> priorityMap = mainDB.hashMap("priority", Serializer.STRING, Serializer.INTEGER).createOrOpen();

            Path dir = directory.resolve("handlers");
            mainMap.entrySet().forEach((entry) -> {
                String name = entry.getKey();
                Path singleDir = dir.resolve(name.toLowerCase());
                Path metaDataFile = singleDir.resolve("metadata.foxdb");
                logger.info("Loading handler \"" + name + "\" from " + singleDir);
                if (Files.exists(metaDataFile) && !Files.isDirectory(metaDataFile)) {
                    String category;
                    String type;
                    Boolean enabled;
                    Integer priority;
                    try (DB metaDB = DBMaker.fileDB(metaDataFile.normalize().toString()).make()) {
                        category = metaDB.exists("category") ? metaDB.atomicString("category").createOrOpen().get() : entry.getValue();
                        type = metaDB.exists("type") ? metaDB.atomicString("type").createOrOpen().get() : typeMap.get(name);
                        enabled = metaDB.exists("enabled") ? metaDB.atomicBoolean("enabled").createOrOpen().get() : enabledMap.get(name);
                        priority = metaDB.exists("priority") ? metaDB.atomicInteger("priority").createOrOpen().get() : priorityMap.get(name);
                    }
                    logger.info("Handler info loaded!  Name: \"" + name +
                            "\",  Category: \"" + category +
                            "\",  Type: \"" + type +
                            "\",  Enabled: " + enabled +
                            ",  Priority: " + priority);
                    if (name.equalsIgnoreCase(GlobalHandler.NAME)) {
                        logger.info("Global handler found! Skipping...");
                        return;
                    }
                    if (category == null) category = "";
                    if (type == null) type = "";
                    IHandler object = null;
                    try {
                        if (category.equalsIgnoreCase("handler"))
                            object = FGFactoryManager.getInstance().createHandler(singleDir, name, type, enabled, priority);
                        else if (category.equalsIgnoreCase("controller"))
                            object = FGFactoryManager.getInstance().createController(singleDir, name, type, enabled, priority);
                        else logger.warn("Category \"" + category + "\" is invalid!");
                    } catch (Exception e) {
                        logger.error("There was an error creating the handler!", e);
                    }
                    if (object != null) {
                        loaded.add(new LoadEntry(object));
                        FGManager.getInstance().addHandler(object);
                        logger.info("Successfully created and added handler \"" + name + "\"!");
                    } else {
                        logger.warn("A handler was unable to be created. Either the metadata is incorrect, or there is no longer a factory available to create it.");
                        if (FGConfigManager.getInstance().cleanupFiles()) {
                            logger.warn("Cleaning up unused files");
                            System.gc();
                            System.runFinalization();
                            deleteDirectory(singleDir);
                        }
                    }
                } else {
                    if (name.equalsIgnoreCase(GlobalHandler.NAME)) {
                        logger.info("Global handler found! Skipping...");
                        return;
                    }
                    logger.warn("Metadata file not found! Skipping...");
                    if (Files.exists(singleDir)) {
                        if (isEmptyDirectory(singleDir)) {
                            logger.warn("Empty handler directory found. Deleting...");
                            try {
                                Files.delete(singleDir);
                            } catch (IOException e) {
                                logger.error("There was an error deleting the handler directory: " + singleDir, e);
                            }
                        } else {
                            if (FGConfigManager.getInstance().cleanupFiles()) {
                                logger.warn("Cleaning up unused files");
                                System.gc();
                                System.runFinalization();
                                deleteDirectory(singleDir);
                            }
                        }
                    }
                }
            });
        }
    }

    public synchronized void loadGlobalHandler() {
        Path path;
        constructDirectory(path = directory.resolve("handlers"));
        constructDirectory(path = path.resolve(GlobalHandler.NAME.toLowerCase()));
        FGManager.getInstance().getGlobalHandler().load(path);
    }

    public void loadLinks() {
        loadRegionLinks();
        Sponge.getServer().getWorlds().forEach(this::loadWorldRegionLinks);
        loadControllerLinks();
    }

    public synchronized void loadRegionLinks() {
        logger.info("Loading region links");
        try (DB mainDB = DBMaker.fileDB(directory.resolve("regions.foxdb").normalize().toString()).make()) {
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();
            linksMap.entrySet().forEach(entry -> {
                IRegion region = FGManager.getInstance().getRegion(entry.getKey());
                if (region != null) {
                    logger.info("Loading links for region \"" + region.getName() + "\"");
                    String handlersString = entry.getValue();
                    if (handlersString != null && !handlersString.isEmpty()) {
                        String[] handlersNames = handlersString.split(",");
                        Arrays.stream(handlersNames).forEach(handlerName -> {
                            IHandler handler = FGManager.getInstance().gethandler(handlerName);
                            if (handler != null) {
                                if (FGManager.getInstance().link(region, handler))
                                    logger.info("Linked region \"" + region.getName() + "\" to handler \"" + handler.getName() + "\"");
                            }
                        });
                    }
                }
            });
        }
    }

    public synchronized void loadWorldRegionLinks(World world) {
        logger.info("Loading world region links for world \"" + world.getName() + "\"");
        try (DB mainDB = DBMaker.fileDB(worldDirectories.get(world.getName()).resolve("wregions.foxdb").normalize().toString()).make()) {
            Map<String, String> linksMap = mainDB.hashMap("links", Serializer.STRING, Serializer.STRING).createOrOpen();
            linksMap.entrySet().forEach(entry -> {
                IRegion region = FGManager.getInstance().getWorldRegion(world, entry.getKey());
                if (region != null) {
                    logger.info("Loading links for world region \"" + region.getName() + "\"");
                    String handlersString = entry.getValue();
                    if (handlersString != null && !handlersString.isEmpty()) {
                        String[] handlersNames = handlersString.split(",");
                        Arrays.stream(handlersNames).forEach(handlerName -> {
                            IHandler handler = FGManager.getInstance().gethandler(handlerName);
                            if (handler != null) {
                                if (FGManager.getInstance().link(region, handler))
                                    logger.info("Linked world region \"" + region.getName() + "\" to handler \"" + handler.getName() + "\"");
                            }
                        });
                    }
                }
            });
        }
    }

    public synchronized void loadControllerLinks() {
        logger.info("Loading controller links");
        Path dir = directory.resolve("handlers");
        for (IController controller : FGManager.getInstance().getControllers()) {
            logger.info("Loading links for controller \"" + controller.getName() + "\"");
            controller.loadLinks(dir.resolve(controller.getName().toLowerCase()));
        }
    }

    public synchronized void addObject(IFGObject object) {
        LoadEntry entry = new LoadEntry(object);
        if (!loaded.contains(entry)) {
            Path singleDirectory = entry.getPath();
            if (Files.exists(singleDirectory)) {
                logger.info("Deleting directory \"" + singleDirectory + "\" to make room for new data.");
                System.gc();
                System.runFinalization();
                deleteDirectory(singleDirectory, true);
            }
            loaded.add(entry);
            if (object instanceof IRegion) {
                if (object instanceof IWorldRegion) {
                    this.saveWorldRegion((IWorldRegion) object);
                } else {
                    this.saveRegion((IRegion) object);
                }
            } else if (object instanceof IHandler) {
                this.saveHandler((IHandler) object);
            }
        }
    }

    public void removeObject(IFGObject object) {
        if (object instanceof IRegion) {
            if (object instanceof IWorldRegion) {
                this.removeWorldRegion((IWorldRegion) object);
            } else {
                this.removeRegion((IRegion) object);
            }
        } else if (object instanceof IHandler) {
            this.removeHandler((IHandler) object);
        }
    }

    public void constructDirectory(Path directory) {
        if (!Files.exists(directory)) {
            try {
                int counter = 1;
                while (true) {
                    try {
                        Files.createDirectory(directory);
                        break;
                    } catch (AccessDeniedException e) {
                        if (counter > 5) throw e;
                        else {
                            logger.error("Unable to create directory: " + directory + "  Trying again in " + counter + " second(s)");
                            try {
                                Thread.sleep(1000 * counter);
                            } catch (InterruptedException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                    counter++;
                }
                logger.info("Created directory: " + directory);
            } catch (IOException e) {
                logger.error("There was an error creating the directory: " + directory, e);
            }
        } else if (!Files.isDirectory(directory)) {
            logger.warn("There is a file at " + directory + " where a directory was expected. Deleting and replacing with a directory...");
            try {
                Files.delete(directory);
                try {
                    int counter = 1;
                    while (true) {
                        try {
                            Files.createDirectory(directory);
                            break;
                        } catch (AccessDeniedException e) {
                            if (counter > 5) throw e;
                            else {
                                logger.error("Unable to create directory: " + directory + "  Trying again in " + counter + " second(s)");
                                try {
                                    Thread.sleep(1000 * counter);
                                } catch (InterruptedException e1) {
                                    e1.printStackTrace();
                                }
                            }
                        }
                        counter++;
                    }
                    logger.info("Created directory: " + directory);
                } catch (IOException e) {
                    logger.error("Error creating the directory: " + directory, e);
                }
            } catch (IOException e) {
                logger.error("Error deleting the file: " + directory, e);
            }
        }
    }

    private Path getDirectory() {
        Path path = Sponge.getGame().getSavesDirectory();
        if (FGConfigManager.getInstance().saveInWorldFolder()) {
            path = path.resolve(Sponge.getServer().getDefaultWorldName());
        } else if (FGConfigManager.getInstance().useConfigFolder()) {
            path = FoxGuardMain.instance().getConfigDirectory();
        }
        path = path.resolve("foxguard");
        constructDirectory(path);
        return path;
    }

    private Path getWorldDirectory(String world) {
        Path path = Sponge.getGame().getSavesDirectory();
        if (FGConfigManager.getInstance().saveWorldRegionsInWorldFolders()) {
            path = path.resolve(Sponge.getServer().getDefaultWorldName());
            if (!Sponge.getServer().getDefaultWorld().get().getWorldName().equalsIgnoreCase(world)) {
                path = path.resolve(world);
            }
            path = path.resolve("foxguard");
        } else {
            if (FGConfigManager.getInstance().useConfigFolder()) {
                path = FoxGuardMain.instance().getConfigDirectory();
            }
            path = path.resolve("foxguard").resolve("worlds").resolve(world);
        }
        constructDirectory(path);
        return path;
    }

    private void deleteDirectory(Path directory) {
        deleteDirectory(directory, false);
    }

    private void deleteDirectory(Path directory, boolean innerOnly) {
        FoxGuardMain.instance().getLogger().info("Deleting directory: " + directory);
        if (Files.exists(directory) && Files.isDirectory(directory))
            try {
                Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            Files.delete(file);
                            logger.info("Deleted file: " + file);
                        } catch (IOException e) {
                            logger.error("There was an error deleting the file: " + file, e);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc == null && (!innerOnly || !Files.isSameFile(dir, directory))) {
                            try {
                                Files.delete(dir);
                                logger.info("Deleted directory: " + dir);
                            } catch (IOException e) {
                                logger.error("There was an error deleting the directory: " + dir, e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                logger.error("There was an error while trying to recursively delete the directory: " + directory, e);
            }
        else if (Files.exists(directory)) {
            logger.warn(directory + "is a file. A directory was expected. Deleting...");
            try {
                Files.delete(directory);
            } catch (IOException e) {
                logger.error("There was an error deleting the file: " + directory, e);
            }
        }
    }

    private boolean isEmptyDirectory(Path directory) {
        if (!Files.exists(directory)) return true;
        if (!Files.isDirectory(directory)) return false;
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            logger.error("Could not read contents of directory: " + directory, e);
            return false;
        }
    }

    private String serializeHandlerList(Collection<IHandler> handlers) {
        StringBuilder builder = new StringBuilder();
        for (Iterator<IHandler> it = handlers.iterator(); it.hasNext(); ) {
            builder.append(it.next().getName());
            if (it.hasNext()) builder.append(",");
        }
        return builder.toString();
    }

    private final class LoadEntry {
        public final String name;
        public final Type type;
        public final String world;

        public LoadEntry(String name, Type type, String world) {
            this.name = name;
            this.type = type;
            this.world = world;
        }

        public LoadEntry(IFGObject object) {
            name = object.getName();
            if (object instanceof IWorldRegion) {
                type = Type.WREGION;
                world = ((IWorldRegion) object).getWorld().getName();
            } else if (object instanceof IRegion) {
                type = Type.REGION;
                world = "";
            } else if (object instanceof IHandler) {
                type = Type.HANDLER;
                world = "";
            } else throw new IllegalArgumentException("Object is not of a valid subtype!");
        }

        public LoadEntry(IFGObject object, String altWorld) {
            name = object.getName();
            if (object instanceof IWorldRegion) {
                type = Type.WREGION;
                world = altWorld;
            } else if (object instanceof IRegion) {
                type = Type.REGION;
                world = "";
            } else if (object instanceof IHandler) {
                type = Type.HANDLER;
                world = "";
            } else throw new IllegalArgumentException("Object is not of a valid subtype!");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LoadEntry entry = (LoadEntry) o;

            if (name != null ? !name.equals(entry.name) : entry.name != null) return false;
            if (type != entry.type) return false;
            return world != null ? world.equals(entry.world) : entry.world == null;

        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + (type != null ? type.hashCode() : 0);
            result = 31 * result + (world != null ? world.hashCode() : 0);
            return result;
        }

        public Path getPath() {
            Path singleDirectory;
            switch (this.type) {
                case REGION:
                    singleDirectory = directory.resolve("regions");
                    break;
                case WREGION:
                    singleDirectory = worldDirectories.get(this.world).resolve("wregions");
                    break;
                case HANDLER:
                    singleDirectory = directory.resolve("handlers");
                    break;
                default:
                    singleDirectory = null;
                    break;
            }
            return singleDirectory.resolve(this.name.toLowerCase());
        }
    }

    public enum Type {
        REGION, WREGION, HANDLER
    }
}
