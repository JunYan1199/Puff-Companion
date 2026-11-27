package com.puffcompanion.compat.rs;

import com.puffcompanion.PuffCompanion;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Refined Storage integration (only loaded with RS). API verified with javap against refinedstorage-1.12.4:
 * {@code API.instance()} -> {@code IRSAPI#getNetworkManager(ServerLevel).getNetwork(BlockPos)} (controllers) and
 * {@code getNetworkNodeManager(ServerLevel).getNode(BlockPos).getNetwork()} (grids, terminals, cables...);
 * {@code INetwork#canRun()}, {@code insertItem(ItemStack, int, Action)} with {@code Action.SIMULATE / PERFORM}.
 */
public final class RsImpl {
    private RsImpl() {}

    @Nullable
    private static INetwork networkAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;   // never force-loads chunks
        try {
            INetwork net = API.instance().getNetworkManager(level).getNetwork(pos);
            if (net == null) {
                INetworkNode node = API.instance().getNetworkNodeManager(level).getNode(pos);
                net = node == null ? null : node.getNetwork();
            }
            return net != null && net.canRun() ? net : null;
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] RS network lookup failed: {}", t.toString());
            return null;
        }
    }

    public static boolean hasNetwork(ServerLevel level, BlockPos pos) {
        return networkAt(level, pos) != null;
    }

    public static ItemStack insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        INetwork net = networkAt(level, pos);
        if (net == null) return stack;
        try {
            // simulate first, then insert only what the network accepts
            ItemStack rest = net.insertItem(stack, stack.getCount(), Action.SIMULATE);
            int accepted = stack.getCount() - (rest == null ? 0 : rest.getCount());
            if (accepted <= 0) return stack;
            ItemStack notInserted = net.insertItem(stack.copyWithCount(accepted), accepted, Action.PERFORM);
            int left = stack.getCount() - accepted + (notInserted == null ? 0 : notInserted.getCount());
            return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        } catch (Throwable t) {
            PuffCompanion.LOGGER.warn("[Puff Companion] RS insert failed: {}", t.toString());
            return stack;
        }
    }
}
