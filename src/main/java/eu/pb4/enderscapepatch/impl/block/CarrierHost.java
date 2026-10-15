package eu.pb4.enderscapepatch.impl.block;

import java.io.IOException;
import java.io.InputStream;
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

    // Each namespace's asset directories, from every mod that ships some.
    private final Map<String, List<Path>> roots = new HashMap<>();

    // An asset from the mods that ship its namespace, else Polymer's copy of the client jar.
    @Override
    public @Nullable InputStream open(String namespace, String path) throws IOException {
        for (Path root : this.roots.computeIfAbsent(namespace, CarrierHost::roots)) {
            Path file = root.resolve(path);
            if (Files.exists(file)) {
                return Files.newInputStream(file);
            }
        }
        Path vanilla = PolymerCommonUtils.getClientJarRoot();
        Path file = vanilla == null ? null : vanilla.resolve("assets/" + namespace + "/" + path);
        return file != null && Files.exists(file) ? Files.newInputStream(file) : null;
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
