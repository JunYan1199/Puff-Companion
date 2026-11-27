package com.puffcompanion.item;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.RsCompat;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * RS resonance deposit charm (Curios "charm"; also works lying in Puff's secret nest). Sneak + right-click a
 * Refined Storage controller / grid / network block to bind it; right-click to edit the 18-slot ghost filter
 * (whitelist / blacklist). While Puff carries it she sends matching items from her nest into the bound
 * network every few seconds - only what the network accepts leaves the nest.
 */
public class RsDepositCharmItem extends Item {
    public static final int FILTER_SIZE = 18;
    private static final String BIND = "RsNetwork";
    private static final int INTERVAL = 100;

    public RsDepositCharmItem(Properties properties) {
        super(properties);
    }

    // ---------------------------------------------------------------- NBT

    @Nullable
    public static GlobalPos binding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(BIND, Tag.TAG_COMPOUND)) return null;
        CompoundTag b = tag.getCompound(BIND);
        ResourceLocation dim = ResourceLocation.tryParse(b.getString("Dim"));
        return dim == null ? null : GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(b.getLong("Pos")));
    }

    public static boolean isWhitelist(ItemStack stack) {
        return !stack.hasTag() || !stack.getTag().contains("Whitelist") || stack.getTag().getBoolean("Whitelist");
    }

    public static void setWhitelist(ItemStack stack, boolean white) {
        stack.getOrCreateTag().putBoolean("Whitelist", white);
    }

    public static SimpleContainer readFilter(ItemStack stack) {
        SimpleContainer c = new SimpleContainer(FILTER_SIZE);
        if (stack.hasTag()) {
            ListTag list = stack.getTag().getList("Filter", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                int slot = t.getInt("Slot");
                if (slot >= 0 && slot < FILTER_SIZE) c.setItem(slot, ItemStack.of(t));
            }
        }
        return c;
    }

    public static void writeFilter(ItemStack stack, SimpleContainer filter) {
        ListTag list = new ListTag();
        for (int i = 0; i < filter.getContainerSize(); i++) {
            ItemStack s = filter.getItem(i);
            if (s.isEmpty()) continue;
            CompoundTag t = s.copyWithCount(1).save(new CompoundTag());
            t.putInt("Slot", i);
            list.add(t);
        }
        stack.getOrCreateTag().put("Filter", list);
    }

    /** Whitelist: only listed items; blacklist: everything but the listed items (item type, NBT ignored). */
    public static boolean passes(ItemStack charm, SimpleContainer filter, ItemStack stack) {
        boolean listed = false;
        for (int i = 0; i < filter.getContainerSize(); i++) {
            if (ItemStack.isSameItem(filter.getItem(i), stack)) {
                listed = true;
                break;
            }
        }
        return isWhitelist(charm) == listed;
    }

    // ---------------------------------------------------------------- use

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockPos pos = ctx.getClickedPos();
        if (!RsCompat.isLoaded() || !RsCompat.hasNetwork((ServerLevel) level, pos)) {
            player.displayClientMessage(Component.translatable(RsCompat.isLoaded()
                    ? "message.puffcompanion.rs_charm.not_network" : "message.puffcompanion.rs_charm.no_rs").withStyle(ChatFormatting.RED), true);
            return InteractionResult.SUCCESS;
        }
        CompoundTag b = new CompoundTag();
        b.putString("Dim", level.dimension().location().toString());
        b.putLong("Pos", pos.asLong());
        ctx.getItemInHand().getOrCreateTag().put(BIND, b);
        player.displayClientMessage(Component.translatable("message.puffcompanion.rs_charm.bound", pos.getX(), pos.getY(), pos.getZ())
                .withStyle(ChatFormatting.AQUA), true);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            NetworkHooks.openScreen(sp, new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.puffcompanion.menu.RsCharmMenu(id, inv, hand),
                    Component.translatable("gui.puffcompanion.rs_charm.title")), buf -> buf.writeEnum(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos pos = binding(stack);
        if (pos == null) {
            tooltip.add(Component.translatable("item.puffcompanion.rs_deposit_charm.unbound").withStyle(ChatFormatting.RED));
        } else {
            BlockPos p = pos.pos();
            tooltip.add(Component.translatable("item.puffcompanion.rs_deposit_charm.bound", p.getX(), p.getY(), p.getZ(),
                    pos.dimension().location().toString()).withStyle(ChatFormatting.AQUA));
        }
        int n = 0;
        SimpleContainer f = readFilter(stack);
        for (int i = 0; i < f.getContainerSize(); i++) if (!f.getItem(i).isEmpty()) n++;
        tooltip.add(Component.translatable(isWhitelist(stack) ? "item.puffcompanion.rs_deposit_charm.whitelist"
                : "item.puffcompanion.rs_deposit_charm.blacklist", n).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.puffcompanion.rs_deposit_charm.hint").withStyle(ChatFormatting.DARK_GRAY));
        if (!RsCompat.isLoaded()) tooltip.add(Component.translatable("item.puffcompanion.rs_deposit_charm.no_rs").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return binding(stack) != null;
    }

    // ---------------------------------------------------------------- Puff side

    /** Server, every tick of a tamed Puff: every 5 s, deposit from her nest through the first bound charm she carries. */
    public static void tickPuff(PuffEntity puff) {
        if (!RsCompat.isLoaded() || puff.tickCount % INTERVAL != 0 || !(puff.level() instanceof ServerLevel here)) return;
        ItemStack charm = findCharm(puff);
        if (charm.isEmpty()) return;
        GlobalPos target = binding(charm);
        ServerLevel level = target == null ? null : here.getServer().getLevel(target.dimension());
        if (level == null) return;
        SimpleContainer filter = readFilter(charm);
        net.minecraft.world.SimpleContainer hoard = puff.getHoard();
        int moved = 0;
        for (int i = 0; i < hoard.getContainerSize(); i++) {
            ItemStack s = hoard.getItem(i);
            if (s.isEmpty() || s.getItem() instanceof RsDepositCharmItem) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id != null && PuffCompanion.MODID.equals(id.getNamespace())) continue;   // never ship Puff's own gear
            if (!passes(charm, filter, s)) continue;
            ItemStack rest = RsCompat.insert(level, target.pos(), s.copy());
            moved += s.getCount() - rest.getCount();
            hoard.setItem(i, rest);
        }
        if (moved > 0) {
            here.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL, puff.getX(), puff.getY() + 0.8, puff.getZ(), 12, 0.3, 0.4, 0.3, 0.2);
        }
    }

    private static ItemStack findCharm(PuffEntity puff) {
        for (List<ItemStack> list : puff.getCurioStore().all().values()) {
            for (ItemStack s : list) if (s.getItem() instanceof RsDepositCharmItem && binding(s) != null) return s;
        }
        for (int i = 0; i < puff.getHoard().getContainerSize(); i++) {
            ItemStack s = puff.getHoard().getItem(i);
            if (s.getItem() instanceof RsDepositCharmItem && binding(s) != null) return s;
        }
        return ItemStack.EMPTY;
    }
}
