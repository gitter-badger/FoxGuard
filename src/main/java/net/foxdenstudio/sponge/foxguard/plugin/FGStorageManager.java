package net.foxdenstudio.sponge.foxguard.plugin;

import net.foxdenstudio.sponge.foxguard.plugin.handler.IHandler;
import net.foxdenstudio.sponge.foxguard.plugin.object.IFGObject;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;

/**
 * Created by Fox on 4/6/2016.
 */
public final class FGStorageManager {
    private static FGStorageManager instance;
    private final Path directory = getDirectory();

    public static FGStorageManager getInstance() {
        if (instance == null) instance = new FGStorageManager();
        return instance;
    }

    public synchronized void saveRegions() {
    }

    public synchronized void saveWorldRegions(World world) {

    }

    public synchronized void saveHandlers() {
    }

    public synchronized void loadRegions() {

    }

    public synchronized void loadWorldRegions(World world) {
    }

    public synchronized void loadHandlers() {
    }

    public synchronized void loadGlobalHandler() {

    }

    public synchronized void loadLinks() {
    }

    public synchronized void loadRegionLinks() {
    }

    public synchronized void loadWorldRegionLinks(World world) {
    }

    public synchronized void loadControllerLinks() {

    }

    public synchronized void addObject(IFGObject object) {
    }

    public synchronized void removeObject(IFGObject object) {
    }

    private Path getDirectory() {
        Path path = Sponge.getGame().getSavesDirectory();
        if (FGConfigManager.getInstance().saveInWorldFolder()) {
            path = path.resolve(Sponge.getServer().getDefaultWorldName());
        } else if (FGConfigManager.getInstance().useConfigFolder()) {
            path = FoxGuardMain.instance().getConfigDirectory();
        }
        return path.resolve("foxguard");
    }

    private Path getWorldDirectory(World world) {
        Path path = Sponge.getGame().getSavesDirectory();
        if (FGConfigManager.getInstance().saveWorldRegionsInWorldFolders()) {
            path = path.resolve(Sponge.getServer().getDefaultWorldName());
            if (!Sponge.getServer().getDefaultWorld().get().equals(world.getProperties())) {
                path = path.resolve(world.getName());
            }
        } else {
            if (FGConfigManager.getInstance().useConfigFolder()) {
                path = FoxGuardMain.instance().getConfigDirectory();
            }
            path = path.resolve("foxguard").resolve("worlds").resolve(world.getName());
        }
        return path;
    }

    private void deleteDirectory(Path directory) {

    }

    private void constructDirectory(Path directory) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (!Files.isDirectory(directory)) {
            try {
                Files.delete(directory);
                Files.createDirectory(directory);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String serializeHandlerList(Collection<IHandler> handlers) {
        StringBuilder builder = new StringBuilder();
        for (Iterator<IHandler> it = handlers.iterator(); it.hasNext(); ) {
            builder.append(it.next());
            if (it.hasNext()) builder.append(",");
        }
        return builder.toString();
    }
}
