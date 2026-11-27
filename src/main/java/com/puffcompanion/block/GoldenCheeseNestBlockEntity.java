package com.puffcompanion.block;

import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffRevival;
import com.puffcompanion.item.CheeseCoreItem;
import com.puffcompanion.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Holds a cheese core + up to 3 cheeses; runs the 3-second revival ritual. */
public class GoldenCheeseNestBlockEntity extends BlockEntity {
    public static final int CHEESE_NEEDED = 3;
    public static final int RITUAL_TICKS = 60;

    private ItemStack core = ItemStack.EMPTY;
    private int cheese;
    private int ritual;

    public GoldenCheeseNestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.GOLDEN_CHEESE_NEST_BE.get(), pos, state);
    }

    public boolean hasCore() {
        return !this.core.isEmpty();
    }

    public int cheese() {
        return this.cheese;
    }

    public boolean ritualRunning() {
        return this.ritual > 0;
    }

    public boolean insertCore(ItemStack stack) {
        if (this.hasCore() || this.ritualRunning() || !(stack.getItem() instanceof CheeseCoreItem)
                || CheeseCoreItem.puffId(stack) == null) return false;
        this.core = stack.split(1);
        this.changed();
        return true;
    }

    /** Adds one cheese; starts the ritual on the third. */
    public boolean addCheese(Player player) {
        if (!this.hasCore() || this.ritualRunning() || this.cheese >= CHEESE_NEEDED) return false;
        this.cheese++;
        if (this.cheese >= CHEESE_NEEDED) {
            this.ritual = RITUAL_TICKS;
            if (this.level != null) {
                this.level.playSound(null, this.worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8F, 1.6F);
            }
        }
        this.changed();
        return true;
    }

    public ItemStack takeCore() {
        if (this.ritualRunning()) return ItemStack.EMPTY;
        ItemStack out = this.core;
        this.core = ItemStack.EMPTY;
        this.cheese = 0;
        this.changed();
        return out;
    }

    public void dropContents(Level level, BlockPos pos) {
        if (this.hasCore()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, this.core);
        this.core = ItemStack.EMPTY;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GoldenCheeseNestBlockEntity be) {
        if (be.ritual <= 0 || !(level instanceof ServerLevel sl)) return;
        be.ritual--;
        double progress = 1.0 - be.ritual / (double) RITUAL_TICKS;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.4, cz = pos.getZ() + 0.5;
        double angle = level.getGameTime() * 0.35;
        double r = 0.9 - 0.5 * progress;
        sl.sendParticles(PuffRevival.GOLD_DUST, cx + Math.cos(angle) * r, cy + progress * 1.6, cz + Math.sin(angle) * r, 2, 0.05, 0.05, 0.05, 0.0);
        sl.sendParticles(PuffRevival.SAKURA_DUST, cx - Math.cos(angle) * r, cy + progress * 1.6, cz - Math.sin(angle) * r, 2, 0.05, 0.05, 0.05, 0.0);
        if (be.ritual % 20 == 0) level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 0.8F + (float) progress);
        if (be.ritual > 0) return;

        UUID id = CheeseCoreItem.puffId(be.core);
        PuffEntity puff = id == null ? null : PuffRevival.reviveFromGraveyard(sl, id, Vec3.atBottomCenterOf(pos.above()));
        Player near = level.getNearestPlayer(cx, cy, cz, 8.0, false);
        if (puff != null) {
            be.core = ItemStack.EMPTY;   // consumed by the revival
            level.playSound(null, pos, SoundEvents.TOTEM_USE, SoundSource.BLOCKS, 0.7F, 1.3F);
        } else if (near != null) {
            // Already revived (e.g. by the bell): the core is spent, keep it inert in the nest for the player to take
            near.displayClientMessage(Component.translatable("message.puffcompanion.nest.already_revived"), true);
        }
        be.cheese = 0;
        be.changed();
    }

    private void changed() {
        this.setChanged();
        if (this.level != null) this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.core.isEmpty()) tag.put("Core", this.core.save(new CompoundTag()));
        tag.putInt("Cheese", this.cheese);
        tag.putInt("Ritual", this.ritual);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.core = tag.contains("Core") ? ItemStack.of(tag.getCompound("Core")) : ItemStack.EMPTY;
        this.cheese = tag.getInt("Cheese");
        this.ritual = tag.getInt("Ritual");
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
