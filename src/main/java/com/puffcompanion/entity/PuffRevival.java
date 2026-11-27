package com.puffcompanion.entity;

import com.puffcompanion.item.RatSpiritBellItem;
import com.puffcompanion.registry.ModEntities;
import com.puffcompanion.world.PuffGraveyard;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/** Snapshot / restore helpers shared by the cheese core, the golden cheese nest and the spirit bell. */
public final class PuffRevival {
    public static final DustParticleOptions GOLD_DUST = new DustParticleOptions(new Vector3f(1.0F, 0.82F, 0.3F), 1.2F);
    public static final DustParticleOptions SAKURA_DUST = new DustParticleOptions(new Vector3f(1.0F, 0.62F, 0.74F), 1.1F);

    private PuffRevival() {}

    /** Full entity snapshot including type id (stats, outfits, stories, nest, gear, curios, owner...). */
    public static CompoundTag snapshot(PuffEntity puff) {
        CompoundTag tag = puff.saveWithoutId(new CompoundTag());
        tag.putString("id", "puffcompanion:puff");
        return tag;
    }

    /** Spawns Puff from a snapshot at {@code pos}, full health, keeping her UUID when it is free. */
    @Nullable
    public static PuffEntity spawnFromSnapshot(ServerLevel level, Vec3 pos, CompoundTag snapshot) {
        PuffEntity puff = ModEntities.PUFF.get().create(level);
        if (puff == null) return null;
        puff.load(snapshot);
        if (level.getEntity(puff.getUUID()) != null) {
            puff.setUUID(UUID.randomUUID());   // never collide with a living copy
        }
        puff.moveTo(pos.x, pos.y, pos.z, puff.getYRot(), 0.0F);
        puff.setDeltaMovement(Vec3.ZERO);
        puff.removeAllEffects();
        puff.clearFire();
        puff.deathTime = 0;
        puff.hurtTime = 0;
        puff.setHealth(puff.getMaxHealth());
        if (!level.addFreshEntity(puff)) return null;
        return puff;
    }

    /** Revives a dead Puff from the graveyard (consumes the record). Null if she is not dead (already revived). */
    @Nullable
    public static PuffEntity reviveFromGraveyard(ServerLevel level, UUID puffId, Vec3 pos) {
        PuffGraveyard graveyard = PuffGraveyard.get(level.getServer());
        CompoundTag snap = graveyard.take(puffId);
        if (snap == null) return null;
        PuffEntity puff = spawnFromSnapshot(level, pos, snap);
        if (puff == null) {
            graveyard.record(puffId, snap);   // keep the record so the player can try again
            return null;
        }
        celebrate(level, puff);
        puff.say("puff.revive", 2, 140, true, PuffEntity.ANIM_SHY);
        if (puff.getOwner() instanceof Player owner) RatSpiritBellItem.markDead(owner, puffId, false);
        return puff;
    }

    public static void celebrate(ServerLevel level, PuffEntity puff) {
        level.sendParticles(GOLD_DUST, puff.getX(), puff.getY() + 1.0, puff.getZ(), 40, 0.6, 0.8, 0.6, 0.02);
        level.sendParticles(SAKURA_DUST, puff.getX(), puff.getY() + 1.0, puff.getZ(), 30, 0.6, 0.8, 0.6, 0.02);
        PuffEventTriggers.hearts(level, puff, 8);
    }

    /** Gives a stack to the player, or drops it at their feet when the inventory is full. */
    public static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }
}
