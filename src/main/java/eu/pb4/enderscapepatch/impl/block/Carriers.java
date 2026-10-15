package eu.pb4.enderscapepatch.impl.block;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Full block states for textured blocks, chosen so a client's own block updates flash them as
 * something alike.
 *
 * <p>A block state named in {@link CarrierTiers} gets its stable slot. A block with an anchor type
 * goes to an anchor group of that type, nearest in colour first, so a plain bookshelf flashes as
 * the anchor's bookshelf; anything else, and an anchor type whose groups are full, goes to the
 * group whose slot block is nearest in colour (CIEDE2000, ties to the lower slot), on this copy's
 * instruments. Polymer without stable slots or predicate requests, as stock Polymer is, gets a
 * normal request instead.
 */
public final class Carriers {

    // A plain note block, which is what a slot nobody holds draws.
    private static final double[] NOTE_BLOCK_LAB = {27.6, 10.6, 16.4};
    // Warnings for slots another mod already took.
    private static final Logger LOGGER = LoggerFactory.getLogger(Carriers.class);

    // Polymer's stable slot and predicate requests, absent from stock Polymer.
    private static final @Nullable MethodHandle STABLE =
            find("requestStableBlock", int.class, PolymerBlockModel[].class);
    // As STABLE, for multipart models.
    private static final @Nullable MethodHandle STABLE_MULTI =
            find("requestStableBlock", int.class, MultiPolymerBlockModel.class);
    // A FULL_BLOCK state the predicate accepts.
    private static final @Nullable MethodHandle BY_PREDICATE = find("requestBlock",
            BlockModelType.class, Predicate.class, PolymerBlockModel[].class);
    // As BY_PREDICATE, for multipart models.
    private static final @Nullable MethodHandle BY_PREDICATE_MULTI = find("requestBlock",
            BlockModelType.class, Predicate.class, MultiPolymerBlockModel.class);

    // Groups with none of their instruments for this copy left; a taken state never frees.
    private static final BitSet FULL_GROUPS = new BitSet();
    // Model colours; built on first use, dropped as the server starts.
    private static @Nullable CarrierColours colours;
    // Each slot block's colour; built and dropped with colours.
    private static double @Nullable [][] slotColours;
    // Whether the drop at server start is registered.
    private static boolean releasing;

    // Static-only; blocks instantiation.
    private Carriers() {}

    /**
     * A FULL_BLOCK state drawn with {@code model}: the block state's stable slot, else a state in
     * an anchor group of its type or in the group nearest in colour, else a normal request.
     *
     * @param block the block's id
     * @param properties the state the model draws, as comma-separated {@code property=value}; it
     *     holds a slot when it has every property the slot names
     * @param anchor {@link CarrierTiers#BOOKSHELF}, or empty for none
     * @param model the models drawn
     * @return the state, or null when Polymer has none left
     */
    public static synchronized @Nullable BlockState request(Identifier block, String properties,
            String anchor, PolymerBlockModel... model) {
        int slot = STABLE == null ? -1 : CarrierTiers.slotOf(block.toString(), properties);
        if (slot >= 0) {
            BlockState stable = invoke(() -> (BlockState) STABLE.invokeExact(slot, model));
            if (stable != null) {
                return stable;
            }
            LOGGER.warn("Stable slot {} for {} is taken", slot, block);
        }
        if (BY_PREDICATE != null && hasRoom(anchor)) {
            BlockState state = clustered(colours().lab(variants(model)), anchor,
                    byPredicate(model));
            if (state != null) {
                return state;
            }
        }
        return PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK, model);
    }

    /**
     * As {@link #request(Identifier, String, String, PolymerBlockModel...)}, for a multipart model;
     * its colour merges the first alternative of every part by area.
     *
     * @param block the block's id
     * @param properties the state the model draws, as comma-separated {@code property=value}
     * @param anchor {@link CarrierTiers#BOOKSHELF}, or empty for none
     * @param model the parts drawn
     * @return the state, or null when Polymer has none left
     */
    public static synchronized @Nullable BlockState request(Identifier block, String properties,
            String anchor, MultiPolymerBlockModel model) {
        int slot = STABLE_MULTI == null ? -1 : CarrierTiers.slotOf(block.toString(), properties);
        if (slot >= 0) {
            BlockState stable = invoke(() -> (BlockState) STABLE_MULTI.invokeExact(slot, model));
            if (stable != null) {
                return stable;
            }
            LOGGER.warn("Stable slot {} for {} is taken", slot, block);
        }
        if (BY_PREDICATE_MULTI != null && hasRoom(anchor)) {
            BlockState state = clustered(colours().partsLab(partVariants(model.models())),
                    anchor, byPredicate(model));
            if (state != null) {
                return state;
            }
        }
        return PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK, model);
    }

    /**
     * Whether a block state holds a stable slot this Polymer can give, for a caller that draws
     * several states with one model and must request through that state first.
     *
     * @param block the block's id
     * @param properties the state, as comma-separated {@code property=value}
     */
    public static boolean holdsStableSlot(Identifier block, String properties) {
        return STABLE != null && CarrierTiers.slotOf(block.toString(), properties) >= 0;
    }

    /**
     * A block state's properties in the form {@link #request} takes.
     *
     * @param state the block state
     * @return comma-separated {@code property=value}
     */
    public static String properties(BlockState state) {
        return state.getValues().map(Property.Value::toString).collect(Collectors.joining(","));
    }

    /**
     * Gives a model that only exists once the pack is built, so its colour is known before.
     *
     * @param id the model's id
     * @param model the model as it goes into the pack
     */
    public static synchronized void defineModel(Identifier id, ModelAsset model) {
        colours().define(id.toString(), JsonParser.parseString(model.toJson()).getAsJsonObject());
    }

    // Predicate requests for a simple model.
    private static PredicateRequest byPredicate(PolymerBlockModel[] model) {
        return predicate -> invoke(() -> (BlockState) BY_PREDICATE.invokeExact(
                BlockModelType.FULL_BLOCK, predicate, model));
    }

    // Predicate requests for a multipart model.
    private static PredicateRequest byPredicate(MultiPolymerBlockModel model) {
        return predicate -> invoke(() -> (BlockState) BY_PREDICATE_MULTI.invokeExact(
                BlockModelType.FULL_BLOCK, predicate, model));
    }

    // Whether a group this anchor type may use still has a free instrument.
    private static boolean hasRoom(String anchor) {
        for (int slot = 0; slot < CarrierTiers.SLOTS.size(); slot++) {
            CarrierTiers.Slot entry = CarrierTiers.SLOTS.get(slot);
            if (!FULL_GROUPS.get(slot) && (!entry.isAnchor() || entry.anchor().equals(anchor))) {
                return true;
            }
        }
        return false;
    }

    // The anchor groups of the type first, then the ordinary ones, each nearest colour first.
    private static @Nullable BlockState clustered(double @Nullable [] lab, String anchor,
            PredicateRequest request) {
        if (lab == null) {
            return null;
        }
        BlockState state = anchor.isEmpty() ? null : nearest(lab,
                slot -> CarrierTiers.SLOTS.get(slot).anchor().equals(anchor),
                CarrierTiers.ANCHOR_INSTRUMENTS, request);
        return state != null ? state : nearest(lab,
                slot -> !CarrierTiers.SLOTS.get(slot).isAnchor(), CarrierHost.INSTRUMENTS,
                request);
    }

    // The first group by colour that the filter accepts and that has a free instrument of ours.
    private static @Nullable BlockState nearest(double[] lab, IntPredicate groups,
            Set<String> instruments, PredicateRequest request) {
        double[][] slotLabs = slotColours();
        double[] distance = new double[slotLabs.length];
        List<Integer> order = new ArrayList<>();
        for (int slot = 0; slot < slotLabs.length; slot++) {
            if (groups.test(slot) && !FULL_GROUPS.get(slot)) {
                distance[slot] = CarrierColours.ciede2000(lab, slotLabs[slot]);
                order.add(slot);
            }
        }
        order.sort(Comparator.<Integer>comparingDouble(slot -> distance[slot])
                .thenComparingInt(slot -> slot));
        for (int slot : order) {
            int note = CarrierTiers.note(slot);
            boolean powered = CarrierTiers.powered(slot);
            // The public count already leaves out the state Polymer keeps for an empty request.
            boolean polymerHasRoom =
                    PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.FULL_BLOCK) > 0;
            BlockState state = request.request(candidate -> candidate.is(Blocks.NOTE_BLOCK)
                    && candidate.getValue(NoteBlock.NOTE) == note
                    && candidate.getValue(NoteBlock.POWERED) == powered
                    && instruments.contains(
                            candidate.getValue(NoteBlock.INSTRUMENT).getSerializedName()));
            if (state != null) {
                return state;
            }
            if (polymerHasRoom) {
                // Polymer had states left, so none of ours is; taken states never free.
                FULL_GROUPS.set(slot);
            }
        }
        return null;
    }

    // The colour source, built on first use; blocks register before the server starts.
    private static CarrierColours colours() {
        if (colours == null) {
            colours = new CarrierColours(new CarrierHost());
            if (!releasing) {
                releasing = true;
                ServerLifecycleEvents.SERVER_STARTING.register(server -> release());
            }
        }
        return colours;
    }

    // Drops the colour caches; a later request rebuilds them.
    private static synchronized void release() {
        colours = null;
        slotColours = null;
    }

    // Each slot block's colour, from its own blockstate; a plain note block when it can't be read.
    private static double[][] slotColours() {
        if (slotColours == null) {
            double[][] labs = new double[CarrierTiers.SLOTS.size()][];
            for (int slot = 0; slot < labs.length; slot++) {
                CarrierTiers.Slot entry = CarrierTiers.SLOTS.get(slot);
                double[] lab = colours().blockstateLab(entry.block(), entry.properties());
                labs[slot] = lab != null ? lab : NOTE_BLOCK_LAB;
            }
            slotColours = labs;
        }
        return slotColours;
    }

    // Every alternative of a variant list as a colour variant.
    private static List<CarrierColours.Variant> variants(PolymerBlockModel[] alternatives) {
        List<CarrierColours.Variant> out = new ArrayList<>();
        for (PolymerBlockModel model : alternatives) {
            out.add(variant(model));
        }
        return out;
    }

    // The first alternative of each part as a colour variant.
    private static List<CarrierColours.Variant> partVariants(List<PolymerBlockModel[]> parts) {
        List<CarrierColours.Variant> out = new ArrayList<>();
        for (PolymerBlockModel[] alternatives : parts) {
            if (alternatives.length > 0) {
                out.add(variant(alternatives[0]));
            }
        }
        return out;
    }

    // One model as a colour variant.
    private static CarrierColours.Variant variant(PolymerBlockModel model) {
        return new CarrierColours.Variant(model.model().toString(), model.x(), model.y(),
                model.weight());
    }

    // A Polymer request through a method handle, its failure rethrown unchecked.
    private static @Nullable BlockState invoke(HandleCall call) {
        try {
            return call.call();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    // Looks up a static PolymerBlockResourceUtils method returning a BlockState, or null.
    private static @Nullable MethodHandle find(String name, Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().findStatic(PolymerBlockResourceUtils.class, name,
                    MethodType.methodType(BlockState.class, parameters));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // A method handle call that may throw anything.
    @FunctionalInterface
    private interface HandleCall {
        // Runs the call.
        @Nullable BlockState call() throws Throwable;
    }

    // A predicate request, simple or multipart.
    @FunctionalInterface
    private interface PredicateRequest {
        // The state the predicate accepts, or null.
        @Nullable BlockState request(Predicate<BlockState> predicate);
    }
}
