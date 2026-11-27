package com.puffcompanion.compat.lootr;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffFakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import noobanidus.mods.lootr.api.LootrAPI;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.api.inventory.ILootrInventory;
import org.jetbrains.annotations.Nullable;

/**
 * Lootr integration (only loaded with Lootr). API verified with javap against lootr-forge-1.20-0.7.35.94:
 * {@code ILootBlockEntity#getTileId / unpackLootTable / getTable / getSeed}, and
 * {@code ILootrAPI#getInventory(Level, UUID, BlockPos, ServerPlayer, BaseContainerBlockEntity, LootFiller,
 * Supplier<ResourceLocation>, LongSupplier)} (no fake-player rejection inside). Lootr keeps one inventory per
 * player UUID, so Puff's proxy player (fixed UUID derived from hers) gets her own, separate share.
 */
public final class LootrImpl {
    private LootrImpl() {}

    @Nullable
    public static BlockPos findNear(PuffEntity puff, int radius) {
        if (!(puff.level() instanceof ServerLevel level)) return null;
        BlockPos center = puff.blockPosition();
        ChunkPos c = new ChunkPos(center);
        int cr = (radius >> 4) + 1;
        BlockPos best = null;
        double bestD = (double) radius * radius;
        for (int cx = c.x - cr; cx <= c.x + cr; cx++) {
            for (int cz = c.z - cr; cz <= c.z + cr; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof ILootBlockEntity) || !(be instanceof BaseContainerBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    if (puff.getSquad().lootrOpened.contains(p.asLong())) continue;
                    double d = p.distSqr(center);
                    if (d <= bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    public static int open(PuffEntity puff, BlockPos pos) {
        if (!(puff.level() instanceof ServerLevel level)) return -1;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ILootBlockEntity loot) || !(be instanceof BaseContainerBlockEntity container)) return -1;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return -1;
        try {
            proxy.syncFrom(puff);
            ILootrInventory inv = LootrAPI.INSTANCE.getInventory(level, loot.getTileId(), pos, proxy, container,
                    loot::unpackLootTable, loot::getTable, loot::getSeed);
            if (inv == null) return -1;
            int count = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.removeItemNoUpdate(i);
                if (s.isEmpty()) continue;
                count += s.getCount();
                puff.stashOrDrop(s);
            }
            inv.setChanged();
            level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6F, 1.2F);
            return count;
        } catch (Throwable t) {
            PuffCompanion.LOGGER.warn("[Puff Companion] Lootr container at {} could not be opened for Puff: {}", pos, t.toString());
            return -1;
        }
    }
}
