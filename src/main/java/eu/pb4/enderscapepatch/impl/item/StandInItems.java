package eu.pb4.enderscapepatch.impl.item;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * RKC Polymer's stand-in policy for block items, found reflectively so this compiles and runs
 * against stock Polymer, where every handle is null and callers keep their own client item. With
 * it, a block item's client item is the one block item a vanilla client can place as the real
 * block's stand-in, pinned to its exact state, or a non-placing firework star.
 *
 * <p>polymer-patch-bundle and enderscape-polymer-patch carry this class unchanged apart from the
 * package line; {@code scripts/check-carrier-copies.sh} keeps them so.
 */
public final class StandInItems {
    private static final @Nullable MethodHandle GET;
    private static final @Nullable MethodHandle ITEM;
    private static final @Nullable MethodHandle PIN;

    static {
        MethodHandle get = null;
        MethodHandle item = null;
        MethodHandle pin = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            String policy = "eu.pb4.polymer.core.api.item.PolymerBlockItemStandIns";
            Class<?> standIns = Class.forName(policy);
            Class<?> decision = Class.forName(policy + "$Decision");
            get = lookup.findStatic(standIns, "get",
                    MethodType.methodType(decision, BlockItem.class, PacketContext.class));
            item = lookup.findVirtual(decision, "item", MethodType.methodType(Item.class));
            pin = lookup.findStatic(standIns, "pin",
                    MethodType.methodType(void.class, ItemStack.class, BlockItem.class,
                            PacketContext.class));
        } catch (ReflectiveOperationException e) {
            get = null;
            item = null;
            pin = null;
        }
        GET = get;
        ITEM = item;
        PIN = pin;
    }

    private StandInItems() {
    }

    /** True when the Polymer in use has the policy. */
    public static boolean available() {
        return GET != null;
    }

    /**
     * The client item the policy gives this block item in this context.
     *
     * @throws IllegalStateException when the policy is unavailable; check {@link #available()}
     */
    public static Item item(BlockItem item, PacketContext context) {
        if (GET == null || ITEM == null) {
            throw new IllegalStateException("stand-in policy unavailable");
        }
        try {
            return (Item) ITEM.invoke(GET.invoke(item, context));
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pins the client stack to the policy's stand-in state; no-op without the policy or a pin. */
    public static void pin(ItemStack out, BlockItem item, PacketContext context) {
        if (PIN == null) {
            return;
        }
        try {
            PIN.invoke(out, item, context);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
