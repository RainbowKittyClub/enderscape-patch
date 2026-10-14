package eu.pb4.enderscapepatch.impl.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import eu.pb4.enderscapepatch.impl.EnderscapePolymerPatch;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Puts each full block on a note block state whose stable-slot block looks like it. A client's own
 * block updates turn a note block into the bass state of its note and powered value, which is that
 * group's stable-slot block (see Polymer's requestStableBlock), so a block grouped with a similar
 * colour flashes subtly instead of as something unrelated. Groups are tried nearest colour first
 * (CIEDE2000, ties to the lower slot). Of each group's 24 free instruments, normal requests fill
 * the first, kalic0re's carriers (sensible-storage) the next four, polymer-patch-bundle the next
 * eleven and this fork the last eight, so no fork depends on another's load order. A group whose
 * slot anchors a block type (the plain bookshelves) is that type's alone and skipped here.
 */
public final class ClusteredCarriers {
    private static final Set<String> INSTRUMENTS = Set.of("trumpet_weathered", "zombie", "skeleton", "creeper",
            "dragon", "wither_skeleton", "piglin", "custom_head");
    private static final double[] NOTE_BLOCK_LAB = {27.6, 10.6, 16.4};

    // Every stable slot in order with its anchor type, identical in polymer-patch-bundle,
    // enderscape-polymer-patch and kalic0re's common/carrier. An anchor's group is sensible-storage's.
    private static final String[][] SLOTS = {
            {"terrestria:redwood_quarter_log", "axis=y,bark_side=northeast", ""}, {"terrestria:redwood_quarter_log", "axis=y,bark_side=northwest", ""},
            {"terrestria:redwood_quarter_log", "axis=y,bark_side=southeast", ""}, {"terrestria:redwood_quarter_log", "axis=y,bark_side=southwest", ""},
            {"terrestria:hemlock_quarter_log", "axis=y,bark_side=northeast", ""}, {"terrestria:hemlock_quarter_log", "axis=y,bark_side=northwest", ""},
            {"terrestria:hemlock_quarter_log", "axis=y,bark_side=southeast", ""}, {"terrestria:hemlock_quarter_log", "axis=y,bark_side=southwest", ""},
            {"terrestria:redwood_log", "axis=y", ""}, {"terrestria:hemlock_log", "axis=y", ""},
            {"traverse:fir_log", "axis=y", ""}, {"woods_and_mires:pine_log", "axis=y", ""},
            {"woods_and_mires:aged_pine_log", "axis=y,mid=false", ""}, {"terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=southeast", ""},
            {"terrestria:cypress_log", "axis=y", ""}, {"terrestria:cypress_quarter_log", "axis=y,bark_side=northeast", ""},
            {"terrestria:cypress_quarter_log", "axis=y,bark_side=northwest", ""}, {"sensiblestorage:fir_bookshelf", "", "bookshelf"},
            {"terrestria:cypress_quarter_log", "axis=y,bark_side=southwest", ""}, {"terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=northeast", ""},
            {"terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=northwest", ""}, {"enderscape:shadoline_pillar", "axis=y", ""},
            {"terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=southwest", ""}, {"terrestria:rainbow_eucalyptus_log", "axis=y", ""},
            {"enderscape:celestial_stem", "axis=y", ""}, {"enderscape:murublight_stem", "axis=y", ""},
            {"cinderscapes:umbral_stem", "axis=y", ""}, {"cinderscapes:scorched_stem", "axis=y", ""},
            {"enderscape:veiled_log", "axis=y", ""}, {"terrestria:andisol", "", ""},
            {"terrestria:volcanic_rock", "", ""}, {"terrestria:volcanic_sand", "", ""},
            {"enderscape:mirestone", "", ""}, {"enderscape:veradite", "", ""},
            {"enderscape:celestial_overgrowth", "facing=up", ""}, {"enderscape:veiled_end_stone", "facing=up", ""},
            {"enderscape:corrupt_overgrowth", "facing=up", ""}, {"enderscape:alluring_magnia", "", ""},
            {"enderscape:repulsive_magnia", "", ""}, {"enderscape:kurodite", "", ""},
            {"cinderscapes:umbral_nylium", "", ""}, {"enderscape:celestial_cap", "", ""},
            {"enderscape:murublight_cap", "", ""}, {"enderscape:void_shale", "stress=0", ""},
            {"terrestria:andisol_grass_block", "snowy=true", ""}, {"cinderscapes:umbral_wart_block", "", ""},
            {"enderscape:stripped_murublight_stem", "axis=y", ""}, {"enderscape:stripped_celestial_stem", "axis=y", ""},
            {"enderscape:stripped_veiled_wood", "axis=y", ""}
    };

    private static final @Nullable MethodHandle REQUEST_BLOCK = findRequestBlock();
    private static final Map<String, List<Path>> ROOTS = new HashMap<>();
    private static @Nullable FileSystem vanilla;
    private static @Nullable CarrierColours colours;
    private static double[] @Nullable [] slotColours;

    private ClusteredCarriers() {
    }

    /**
     * A FULL_BLOCK state in the group whose slot block is nearest in colour that has a free
     * instrument, or null when Polymer can't place by predicate or the model has no colour; the
     * caller then requests a normal state.
     */
    public static synchronized @Nullable BlockState request(List<StateModelVariant> variants, PolymerBlockModel[] model) {
        if (REQUEST_BLOCK == null) {
            return null;
        }
        if (colours == null) {
            colours = new CarrierColours(ClusteredCarriers::open);
            slotColours = new double[SLOTS.length][];
            for (int slot = 0; slot < SLOTS.length; slot++) {
                double[] lab = slotLab(SLOTS[slot][0], SLOTS[slot][1]);
                slotColours[slot] = lab != null ? lab : NOTE_BLOCK_LAB;
            }
        }
        var list = new ArrayList<CarrierColours.Variant>();
        for (var variant : variants) {
            list.add(new CarrierColours.Variant(variant.model().toString(), variant.x(), variant.y(), variant.weigth()));
        }
        double[] lab = colours.lab(list);
        if (lab == null) {
            return null;
        }
        var order = new ArrayList<Integer>();
        var distance = new double[SLOTS.length];
        for (int slot = 0; slot < SLOTS.length; slot++) {
            distance[slot] = CarrierColours.ciede2000(lab, slotColours[slot]);
            order.add(slot);
        }
        order.sort(Comparator.<Integer>comparingDouble(slot -> distance[slot]).thenComparingInt(slot -> slot));
        for (int slot : order) {
            if (!SLOTS[slot][2].isEmpty()) {
                continue;
            }
            int note = slot < 48 ? slot / 2 + 1 : 0;
            boolean powered = slot < 48 ? slot % 2 == 1 : true;
            Predicate<BlockState> predicate = state -> state.is(Blocks.NOTE_BLOCK) && state.getValue(NoteBlock.NOTE) == note
                && state.getValue(NoteBlock.POWERED) == powered && INSTRUMENTS.contains(state.getValue(NoteBlock.INSTRUMENT).getSerializedName());
            try {
                var state = (BlockState) REQUEST_BLOCK.invokeExact(BlockModelType.FULL_BLOCK, predicate, model);
                if (state != null) {
                    EnderscapePolymerPatch.LOGGER.debug("{} goes with slot {} ({} ΔE)", variants.getFirst().model(), slot, distance[slot]);
                    return state;
                }
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    private static double @Nullable [] slotLab(String block, String properties) {
        int colon = block.indexOf(':');
        try (InputStream in = open(block.substring(0, colon), "blockstates/" + block.substring(colon + 1) + ".json")) {
            if (in == null) {
                return null;
            }
            JsonObject variants = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
            Map<String, String> wanted = parse(properties);
            for (var entry : variants.entrySet()) {
                if (parse(entry.getKey()).entrySet().containsAll(wanted.entrySet())) {
                    var list = new ArrayList<CarrierColours.Variant>();
                    JsonElement value = entry.getValue();
                    for (var element : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value)) {
                        JsonObject variant = element.getAsJsonObject();
                        list.add(new CarrierColours.Variant(variant.get("model").getAsString(),
                            variant.has("x") ? variant.get("x").getAsInt() : 0, variant.has("y") ? variant.get("y").getAsInt() : 0,
                            variant.has("weight") ? variant.get("weight").getAsInt() : 1));
                    }
                    return colours.lab(list);
                }
            }
        } catch (Exception e) {
            EnderscapePolymerPatch.LOGGER.debug("No colour for stable slot block {}", block, e);
        }
        return null;
    }

    private static @Nullable InputStream open(String namespace, String path) throws java.io.IOException {
        for (Path root : ROOTS.computeIfAbsent(namespace, ClusteredCarriers::roots)) {
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

    // Each mod's assets/<namespace> directory, in the loader's mod order
    private static List<Path> roots(String namespace) {
        var out = new ArrayList<Path>();
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

    private static Map<String, String> parse(String properties) {
        var out = new HashMap<String, String>();
        for (String pair : properties.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    // Polymer without predicate requests: every block falls back to a normal request
    private static @Nullable MethodHandle findRequestBlock() {
        try {
            return MethodHandles.publicLookup().findStatic(PolymerBlockResourceUtils.class, "requestBlock",
                MethodType.methodType(BlockState.class, BlockModelType.class, Predicate.class, PolymerBlockModel[].class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
