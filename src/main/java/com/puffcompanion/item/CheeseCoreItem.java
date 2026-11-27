package com.puffcompanion.item;

import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffOutfit;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Spirit cheese core: dropped where Puff dies. Holds her identity (the full snapshot lives in
 * {@link com.puffcompanion.world.PuffGraveyard}). As a dropped item it is fire / lava / cactus / explosion
 * proof, never despawns, and is rescued from the void back to the owner (or world spawn).
 */
public class CheeseCoreItem extends Item {
    public static final String TAG_PUFF = "PuffUUID";
    public static final String TAG_OWNER = "Owner";
    public static final String TAG_NAME = "PuffName";
    public static final String TAG_STAGE = "Stage";
    public static final String TAG_OUTFIT = "Outfit";
    public static final String TAG_AFFECTION = "Affection";

    public CheeseCoreItem(Properties props) {
        super(props.stacksTo(1).fireResistant().rarity(Rarity.EPIC));
    }

    public static ItemStack create(PuffEntity puff) {
        ItemStack stack = new ItemStack(com.puffcompanion.registry.ModItems.CHEESE_CORE.get());
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(TAG_PUFF, puff.getUUID());
        if (puff.getOwnerUUID() != null) tag.putUUID(TAG_OWNER, puff.getOwnerUUID());
        tag.putString(TAG_NAME, Component.Serializer.toJson(puff.getDisplayName()));
        tag.putInt(TAG_STAGE, puff.getGrowthStage());
        tag.putString(TAG_OUTFIT, puff.getCurrentOutfit());
        tag.putInt(TAG_AFFECTION, puff.getAffection());
        return stack;
    }

    @Nullable
    public static UUID puffId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_PUFF) ? tag.getUUID(TAG_PUFF) : null;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /** Forge per-tick hook for the dropped item: indestructible, never despawns, rescued from the void. */
    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        Level level = entity.level();
        if (level.isClientSide) return false;
        entity.setUnlimitedLifetime();
        entity.setInvulnerable(true);
        if (entity.getY() < level.getMinBuildHeight() + 2) {
            rescueFromVoid(stack.copy(), (ServerLevel) level);
            entity.discard();
            return true;
        }
        return false;
    }

    private static void rescueFromVoid(ItemStack stack, ServerLevel level) {
        CompoundTag tag = stack.getTag();
        ServerPlayer owner = tag != null && tag.hasUUID(TAG_OWNER)
                ? level.getServer().getPlayerList().getPlayer(tag.getUUID(TAG_OWNER)) : null;
        if (owner != null) {
            if (!owner.getInventory().add(stack)) owner.drop(stack, false);
            owner.displayClientMessage(Component.translatable("message.puffcompanion.core_rescued"), true);
            return;
        }
        ServerLevel overworld = level.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        ItemEntity safe = new ItemEntity(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5, stack);
        safe.setUnlimitedLifetime();
        safe.setInvulnerable(true);
        overworld.addFreshEntity(safe);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_PUFF)) {
            lines.add(Component.translatable("tooltip.puffcompanion.core.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        Component name = Component.Serializer.fromJson(tag.getString(TAG_NAME));
        lines.add(Component.translatable("tooltip.puffcompanion.core.name",
                name != null ? name : Component.literal("?")).withStyle(ChatFormatting.LIGHT_PURPLE));
        PuffOutfit outfit = PuffOutfit.byId(tag.getString(TAG_OUTFIT));
        lines.add(Component.translatable("tooltip.puffcompanion.puff_summary", tag.getInt(TAG_STAGE),
                outfit != null ? Component.translatable(outfit.nameKey()) : Component.literal("?"),
                tag.getInt(TAG_AFFECTION)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.puffcompanion.core.hint").withStyle(ChatFormatting.GOLD));
    }
}
