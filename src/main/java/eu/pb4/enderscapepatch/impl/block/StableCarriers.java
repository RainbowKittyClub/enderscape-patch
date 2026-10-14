package eu.pb4.enderscapepatch.impl.block;

import eu.pb4.enderscapepatch.impl.EnderscapePolymerPatch;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Blocks that get one of Polymer's stable full block slots, whose client-side state survives the
 * client's own block updates, so breaking or placing next to a stem or End terrain doesn't show
 * other blocks along its column until the server answers. Slots are fixed so the pack never depends
 * on registration order; polymer-patch-bundle holds the others (0-23, 26, 27, 29-31, 40, 44-48).
 */
public final class StableCarriers {
    private static final List<Entry> ENTRIES = List.of(
        entry(24, "enderscape:celestial_stem", "axis=y"),
        entry(25, "enderscape:murublight_stem", "axis=y"),
        entry(28, "enderscape:veiled_log", "axis=y"),
        entry(32, "enderscape:mirestone", ""),
        entry(33, "enderscape:veradite", ""),
        entry(34, "enderscape:celestial_overgrowth", "facing=up"),
        entry(35, "enderscape:veiled_end_stone", "facing=up"),
        entry(36, "enderscape:corrupt_overgrowth", "facing=up"),
        entry(37, "enderscape:alluring_magnia", ""),
        entry(38, "enderscape:repulsive_magnia", ""),
        entry(39, "enderscape:kurodite", ""),
        entry(41, "enderscape:celestial_cap", ""),
        entry(42, "enderscape:murublight_cap", ""),
        entry(43, "enderscape:void_shale", "stress=0")
    );
    private static final @Nullable MethodHandle REQUEST_STABLE_BLOCK = findRequestStableBlock();

    private StableCarriers() {
    }

    /**
     * The stable state for this block state, or null when it has no slot, the slot is taken, or
     * Polymer has no stable slots; the caller then requests a normal state.
     */
    public static @Nullable BlockState request(Identifier id, BlockState state, PolymerBlockModel[] model) {
        if (REQUEST_STABLE_BLOCK == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.block.equals(id) && entry.matches(state)) {
                try {
                    var stable = (BlockState) REQUEST_STABLE_BLOCK.invokeExact(entry.slot, model);
                    if (stable == null) {
                        EnderscapePolymerPatch.LOGGER.warn("Stable slot {} for {} is taken", entry.slot, id);
                    }
                    return stable;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }

    private static Entry entry(int slot, String block, String properties) {
        var values = new ArrayList<Map.Entry<String, String>>();
        for (String pair : properties.split(",")) {
            if (!pair.isEmpty()) {
                var split = pair.split("=", 2);
                values.add(Map.entry(split[0], split[1]));
            }
        }
        return new Entry(slot, Identifier.parse(block), List.copyOf(values));
    }

    // Polymer without stable slots: every block falls back to a normal request
    private static @Nullable MethodHandle findRequestStableBlock() {
        try {
            return MethodHandles.publicLookup().findStatic(PolymerBlockResourceUtils.class, "requestStableBlock",
                    MethodType.methodType(BlockState.class, int.class, PolymerBlockModel[].class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private record Entry(int slot, Identifier block, List<Map.Entry<String, String>> properties) {
        boolean matches(BlockState state) {
            for (var value : this.properties) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(value.getKey());
                if (property == null || !value.getValue().equals(name(property, state))) {
                    return false;
                }
            }
            return true;
        }

        private static <T extends Comparable<T>> String name(Property<T> property, BlockState state) {
            return property.getName(state.getValue(property));
        }
    }
}
