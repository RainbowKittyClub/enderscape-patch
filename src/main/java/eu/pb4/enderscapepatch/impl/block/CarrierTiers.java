package eu.pb4.enderscapepatch.impl.block;

import java.util.List;
import java.util.Set;

/**
 * Polymer's 49 stable full block slots, in slot order, and how each slot's group of note block
 * states is split among the three copies of this package.
 *
 * <p>A client's own block updates turn a note block into the bass state of its note and powered
 * value, which is that group's stable slot, so every block in a group flashes as its slot block.
 * polymer-patch-bundle, enderscape-polymer-patch and kalic0re carry this package unchanged apart
 * from {@code CarrierHost}. Each group has 24 free instruments: {@code snare} for normal requests,
 * {@code hat} to {@code guitar} for kalic0re, {@code chime} to {@code trumpet_oxidized} for
 * polymer-patch-bundle and {@code trumpet_weathered} to {@code custom_head} for
 * enderscape-polymer-patch, so no copy depends on another's load order. A group whose slot
 * anchors a type belongs wholly to kalic0re's blocks of that type, {@code snare} included.
 */
public final class CarrierTiers {

    /** Anchor type of the plain bookshelf slot; the other plain bookshelves fill its group. */
    public static final String BOOKSHELF = "bookshelf";

    // kalic0re's instruments in an ordinary group.
    static final Set<String> KALIC0RE_INSTRUMENTS = Set.of("hat", "flute", "bell", "guitar");
    // polymer-patch-bundle's instruments in an ordinary group.
    static final Set<String> PATCH_BUNDLE_INSTRUMENTS = Set.of("chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet",
            "trumpet_exposed", "trumpet_oxidized");
    // enderscape-polymer-patch's instruments in an ordinary group.
    static final Set<String> ENDERSCAPE_INSTRUMENTS = Set.of("trumpet_weathered", "zombie",
            "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head");
    // kalic0re's instruments in a group its blocks anchor: all 24, snare included.
    static final Set<String> ANCHOR_INSTRUMENTS = Set.of("snare", "hat", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo",
            "pling", "trumpet", "trumpet_exposed", "trumpet_oxidized", "trumpet_weathered",
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head");

    // Every slot, in order; natural upright trunks first, then the most visible terrain.
    static final List<Slot> SLOTS = List.of(
            slot("terrestria:redwood_quarter_log", "axis=y,bark_side=northeast"),
            slot("terrestria:redwood_quarter_log", "axis=y,bark_side=northwest"),
            slot("terrestria:redwood_quarter_log", "axis=y,bark_side=southeast"),
            slot("terrestria:redwood_quarter_log", "axis=y,bark_side=southwest"),
            slot("terrestria:hemlock_quarter_log", "axis=y,bark_side=northeast"),
            slot("terrestria:hemlock_quarter_log", "axis=y,bark_side=northwest"),
            slot("terrestria:hemlock_quarter_log", "axis=y,bark_side=southeast"),
            slot("terrestria:hemlock_quarter_log", "axis=y,bark_side=southwest"),
            slot("terrestria:redwood_log", "axis=y"),
            slot("terrestria:hemlock_log", "axis=y"),
            slot("traverse:fir_log", "axis=y"),
            slot("woods_and_mires:pine_log", "axis=y"),
            slot("woods_and_mires:aged_pine_log", "axis=y,mid=false"),
            slot("terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=southeast"),
            slot("terrestria:cypress_log", "axis=y"),
            slot("terrestria:cypress_quarter_log", "axis=y,bark_side=northeast"),
            slot("terrestria:cypress_quarter_log", "axis=y,bark_side=northwest"),
            slot("sensiblestorage:fir_bookshelf", "", BOOKSHELF),
            slot("terrestria:cypress_quarter_log", "axis=y,bark_side=southwest"),
            slot("terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=northeast"),
            slot("terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=northwest"),
            slot("enderscape:shadoline_pillar", "axis=y"),
            slot("terrestria:rainbow_eucalyptus_quarter_log", "axis=y,bark_side=southwest"),
            slot("terrestria:rainbow_eucalyptus_log", "axis=y"),
            slot("enderscape:celestial_stem", "axis=y"),
            slot("enderscape:murublight_stem", "axis=y"),
            slot("cinderscapes:umbral_stem", "axis=y"),
            slot("cinderscapes:scorched_stem", "axis=y"),
            slot("enderscape:veiled_log", "axis=y"),
            slot("terrestria:andisol", ""),
            slot("terrestria:volcanic_rock", ""),
            slot("terrestria:volcanic_sand", ""),
            slot("enderscape:mirestone", ""),
            slot("enderscape:veradite", ""),
            slot("enderscape:celestial_overgrowth", "facing=up"),
            slot("enderscape:veiled_end_stone", "facing=up"),
            slot("enderscape:corrupt_overgrowth", "facing=up"),
            slot("enderscape:alluring_magnia", ""),
            slot("enderscape:repulsive_magnia", ""),
            slot("enderscape:kurodite", ""),
            slot("cinderscapes:umbral_nylium", ""),
            slot("enderscape:celestial_cap", ""),
            slot("enderscape:murublight_cap", ""),
            slot("enderscape:void_shale", "stress=0"),
            slot("terrestria:andisol_grass_block", "snowy=true"),
            slot("cinderscapes:umbral_wart_block", ""),
            slot("enderscape:stripped_murublight_stem", "axis=y"),
            slot("enderscape:stripped_celestial_stem", "axis=y"),
            slot("enderscape:stripped_veiled_wood", "axis=y"));

    // Static-only; blocks instantiation.
    private CarrierTiers() {}

    // Note value of a slot's group, for slot 0 to 48.
    static int note(int slot) {
        return slot < 48 ? slot / 2 + 1 : 0;
    }

    // Powered value of a slot's group, for slot 0 to 48.
    static boolean powered(int slot) {
        return slot >= 48 || slot % 2 == 1;
    }

    // The slot a block state holds, or -1: the first of the block's slots whose properties it has.
    static int slotOf(String block, String properties) {
        List<String> state = split(properties);
        for (int slot = 0; slot < SLOTS.size(); slot++) {
            Slot entry = SLOTS.get(slot);
            if (entry.block().equals(block) && state.containsAll(entry.properties())) {
                return slot;
            }
        }
        return -1;
    }

    // Comma-separated property=value pairs as a list; empty for none.
    static List<String> split(String properties) {
        return properties.isEmpty() ? List.of() : List.of(properties.split(","));
    }

    // A slot no type anchors.
    private static Slot slot(String block, String properties) {
        return slot(block, properties, "");
    }

    // A slot, its properties split once.
    private static Slot slot(String block, String properties, String anchor) {
        return new Slot(block, split(properties), anchor);
    }

    // One stable slot: the block it holds, its property=value pairs, its anchor type or "".
    record Slot(String block, List<String> properties, String anchor) {

        // Whether this slot anchors a block type.
        boolean isAnchor() {
            return !anchor.isEmpty();
        }
    }
}
