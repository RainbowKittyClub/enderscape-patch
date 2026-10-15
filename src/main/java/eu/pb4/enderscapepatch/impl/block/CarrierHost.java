package eu.pb4.enderscapepatch.impl.block;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

// The one file of this package that differs between its three copies: the copy's instruments and
// where it reads models, blockstates and textures.
final class CarrierHost implements CarrierColours.Assets {

    // This copy's instruments in an ordinary group.
    static final Set<String> INSTRUMENTS = CarrierTiers.ENDERSCAPE_INSTRUMENTS;

    // The vanilla client jar, for vanilla parent models and textures; opened on first use.
    private static @Nullable FileSystem vanilla;

    // Each namespace's asset directories, from every mod that ships some.
    private final Map<String, List<Path>> roots = new HashMap<>();

    // An asset from the mods that ship its namespace, else the vanilla client jar.
    @Override
    public @Nullable InputStream open(String namespace, String path) throws IOException {
        for (Path root : this.roots.computeIfAbsent(namespace, CarrierHost::roots)) {
            Path file = root.resolve(path);
            if (Files.exists(file)) {
                return Files.newInputStream(file);
            }
        }
        if (vanilla == null) {
            vanilla = FileSystems.newFileSystem(PolymerCommonUtils.getClientJar());
        }
        Path file = vanilla.getPath("/assets/" + namespace + "/" + path);
        return Files.exists(file) ? Files.newInputStream(file) : null;
    }

    // Each mod's assets/<namespace> directory, in the loader's mod order.
    private static List<Path> roots(String namespace) {
        List<Path> out = new ArrayList<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                Path assets = root.resolve("assets/" + namespace);
                if (Files.isDirectory(assets)) {
                    out.add(assets);
                }
            }
        }
        return out;
    }
}
