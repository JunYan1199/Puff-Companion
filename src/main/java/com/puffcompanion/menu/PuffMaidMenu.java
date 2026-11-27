package com.puffcompanion.menu;

import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffHoard;
import com.puffcompanion.network.PuffNetwork;
import com.puffcompanion.network.PuffStatsPacket;
import com.puffcompanion.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Touhou-Little-Maid style management panel: 6 equipment slots bound to Puff (4 armor + both hands), her 18-slot secret
 * nest, optional Curios accessory slots (layout sent from the server), and the player's inventory.
 * Slot layout constants are shared with {@code PuffMaidScreen}.
 */
public class PuffMaidMenu extends AbstractContainerMenu {
    // ---- layout (relative to the screen's leftPos / topPos) ----
    public static final int WIDTH = 290;
    public static final int HEIGHT = 182;
    public static final int EQUIP_X = 30;
    public static final int EQUIP_GAP = 32;
    public static final int EQUIP_Y = 152;
    /** Armor column: inside the left edge of the 3D preview window, helmet on top. */
    public static final int ARMOR_X = 8;
    public static final int ARMOR_Y = 24;
    public static final int HOARD_COLS = 6;
    public static final int HOARD_X = 147;
    public static final int HOARD_Y = 33;
    public static final int INV_X = 120;
    public static final int INV_Y = 101;
    public static final int HOTBAR_Y = 159;
    // Curios page: 3 rows of up to 7 slots per page, row label on the left
    public static final int CURIO_X = 158;
    public static final int CURIO_Y = 35;
    public static final int CURIO_PER_ROW = 7;
    public static final int CURIO_ROWS_PER_PAGE = 3;

    public static final int TAB_NEST = 0;
    public static final int TAB_WARDROBE = 1;
    public static final int TAB_MEMORIES = 2;
    public static final int TAB_STATS = 3;
    public static final int TAB_CURIOS = 4;
    public static final int TAB_SQUAD = 5;
    /** Squad tab: Create blueprint slot. */
    public static final int BLUEPRINT_X = 122;
    public static final int BLUEPRINT_Y = 137;

    /** One visual row of curio slots (a slot type may span several rows). */
    public record CurioRow(String type, int firstIndex, int count, int page, int rowInPage) {}

    private final int armorStart;
    private final int equipEnd;
    private final int hoardEnd;
    private final int invEnd;
    private final int curioEnd;
    private final int blueprintIndex;
    private int squadTimer;

    @Nullable private final PuffEntity puff;
    private final int puffId;
    private final Player player;
    private final List<CuriosCompat.SlotGroup> layout;
    private final List<CurioRow> curioRows = new ArrayList<>();
    private final int curioPages;
    private int statsTimer;

    /** Client-side page state; slots are only shown (and clickable) on the matching page. */
    public int tab = TAB_NEST;
    public int curioPage = 0;

    /** Server: bound to the real entity. */
    public PuffMaidMenu(int id, Inventory inv, PuffEntity puff, List<CuriosCompat.SlotGroup> layout) {
        this(id, inv, puff, puff.getId(), puff.getHoard(), new PuffEquipmentContainer(puff),
                new PuffCurioContainer(puff.getCurioStore(), layout), puff.getSquad().blueprint, layout);
    }

    /** Client: contents are synced from the server into placeholder containers. */
    public static PuffMaidMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int groups = buf.readVarInt();
        List<CuriosCompat.SlotGroup> layout = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < groups; i++) {
            CuriosCompat.SlotGroup g = new CuriosCompat.SlotGroup(buf.readUtf(64), buf.readVarInt());
            layout.add(g);
            total += g.count();
        }
        Entity e = inv.player.level().getEntity(entityId);
        return new PuffMaidMenu(id, inv, e instanceof PuffEntity p ? p : null, entityId,
                new SimpleContainer(PuffHoard.SIZE), new SimpleContainer(PuffEquipmentContainer.SLOTS.length),
                new SimpleContainer(Math.max(1, total)), new SimpleContainer(1), layout);
    }

    public static void writeOpenData(FriendlyByteBuf buf, int entityId, List<CuriosCompat.SlotGroup> layout) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(layout.size());
        for (CuriosCompat.SlotGroup g : layout) {
            buf.writeUtf(g.id(), 64);
            buf.writeVarInt(g.count());
        }
    }

    private PuffMaidMenu(int id, Inventory inv, @Nullable PuffEntity puff, int puffId, Container hoard,
                         Container equipment, Container curios, Container blueprint, List<CuriosCompat.SlotGroup> layout) {
        super(ModMenus.PUFF_MAID.get(), id);
        this.puff = puff;
        this.puffId = puffId;
        this.player = inv.player;
        this.layout = List.copyOf(layout);
        checkContainerSize(hoard, PuffHoard.SIZE);

        for (int i = 0; i < PuffEquipmentContainer.ARMOR_START; i++) {
            this.addSlot(new PageSlot(equipment, i, EQUIP_X + i * EQUIP_GAP, EQUIP_Y, TAB_NEST) {
                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        this.armorStart = this.slots.size();
        for (int i = PuffEquipmentContainer.ARMOR_START; i < PuffEquipmentContainer.SLOTS.length; i++) {
            int row = i - PuffEquipmentContainer.ARMOR_START;
            this.addSlot(new ArmorSlot(equipment, i, ARMOR_X, ARMOR_Y + row * 18, PuffEquipmentContainer.SLOTS[i]));
        }
        this.equipEnd = this.slots.size();
        for (int i = 0; i < PuffHoard.SIZE; i++) {
            this.addSlot(new PageSlot(hoard, i, HOARD_X + (i % HOARD_COLS) * 18, HOARD_Y + (i / HOARD_COLS) * 18, TAB_NEST));
        }
        this.hoardEnd = this.slots.size();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new InventorySlot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new InventorySlot(inv, col, INV_X + col * 18, HOTBAR_Y));
        }
        this.invEnd = this.slots.size();

        // Curios rows / pages
        int containerIndex = 0;
        int rowIndex = 0;
        for (CuriosCompat.SlotGroup g : this.layout) {
            for (int start = 0; start < g.count(); start += CURIO_PER_ROW) {
                int count = Math.min(CURIO_PER_ROW, g.count() - start);
                int page = rowIndex / CURIO_ROWS_PER_PAGE;
                int rowInPage = rowIndex % CURIO_ROWS_PER_PAGE;
                this.curioRows.add(new CurioRow(g.id(), start, count, page, rowInPage));
                for (int j = 0; j < count; j++) {
                    this.addSlot(new CurioSlot(curios, containerIndex++, CURIO_X + j * 18, CURIO_Y + rowInPage * 18, g.id(), start + j, page));
                }
                rowIndex++;
            }
        }
        this.curioEnd = this.slots.size();
        this.addSlot(new PageSlot(blueprint, 0, BLUEPRINT_X, BLUEPRINT_Y, TAB_SQUAD) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return com.puffcompanion.compat.CreateCompat.isSchematic(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean isActive() {
                return super.isActive() && com.puffcompanion.compat.CreateCompat.isLoaded();
            }
        });
        this.blueprintIndex = this.slots.size() - 1;
        this.curioPages = Math.max(1, (rowIndex + CURIO_ROWS_PER_PAGE - 1) / CURIO_ROWS_PER_PAGE);
    }

    @Nullable
    public PuffEntity getPuff() {
        return this.puff;
    }

    public int getPuffId() {
        return this.puffId;
    }

    public boolean hasCurios() {
        return !this.layout.isEmpty();
    }

    public List<CurioRow> curioRows() {
        return this.curioRows;
    }

    public int curioPages() {
        return this.curioPages;
    }

    /** Shift-click: Puff's slots -> player inventory; player inventory -> a fitting curio slot, else the nest. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean fromPlayer = index >= this.hoardEnd && index < this.invEnd;
        if (index == this.blueprintIndex) fromPlayer = false;
        if (!fromPlayer) {
            if (!this.moveItemStackTo(stack, this.hoardEnd, this.invEnd, true)) return ItemStack.EMPTY;
        } else {
            // blueprint -> blueprint slot; armor -> its armor slot; then a fitting curio slot; then the nest
            boolean moved = com.puffcompanion.compat.CreateCompat.isSchematic(stack)
                    && this.moveItemStackTo(stack, this.blueprintIndex, this.blueprintIndex + 1, false);
            if (!moved) moved = this.moveItemStackTo(stack, this.armorStart, this.equipEnd, false);
            if (!moved) moved = this.curioEnd > this.invEnd && this.moveItemStackTo(stack, this.invEnd, this.curioEnd, false);
            if (moved && this.player.level().isClientSide && this.tab == TAB_CURIOS) {
                for (int i = this.invEnd; i < this.curioEnd; i++) {
                    if (this.slots.get(i) instanceof CurioSlot cs && cs.hasItem() && ItemStack.isSameItemSameTags(cs.getItem(), original)) {
                        this.curioPage = cs.page;
                        break;
                    }
                }
            }
            if (!moved && !this.moveItemStackTo(stack, this.equipEnd, this.hoardEnd, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    /** Server: also push a fresh attribute snapshot twice a second for the stats page. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (this.puff != null && this.player instanceof ServerPlayer sp && --this.statsTimer <= 0) {
            this.statsTimer = 10;
            PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), PuffStatsPacket.of(this.puff));
        }
        if (this.puff != null && this.player instanceof ServerPlayer sp && --this.squadTimer <= 0) {
            this.squadTimer = 20;
            PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                    com.puffcompanion.network.PuffSquadSyncPacket.of(this.puff));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return this.puff == null || (this.puff.isAlive() && this.puff.isOwnedBy(player)
                && this.puff.distanceToSqr(player) <= PuffEntity.INTERACT_RANGE * PuffEntity.INTERACT_RANGE);
    }

    /** Slot shown only on one page. */
    public class PageSlot extends Slot {
        private final int page;

        PageSlot(Container container, int index, int x, int y, int page) {
            super(container, index, x, y);
            this.page = page;
        }

        @Override
        public boolean isActive() {
            return PuffMaidMenu.this.tab == this.page;
        }
    }

    /** Helmet / chestplate / leggings / boots: only items that can be worn in that slot (vanilla + modded). */
    public class ArmorSlot extends PageSlot {
        private static final net.minecraft.resources.ResourceLocation[] ICONS = {
                InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
                InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET};
        public final net.minecraft.world.entity.EquipmentSlot slot;

        ArmorSlot(Container container, int index, int x, int y, net.minecraft.world.entity.EquipmentSlot slot) {
            super(container, index, x, y, TAB_NEST);
            this.slot = slot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            Entity wearer = PuffMaidMenu.this.puff != null ? PuffMaidMenu.this.puff : PuffMaidMenu.this.player;
            return !stack.isEmpty() && stack.canEquip(this.slot, wearer);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> getNoItemIcon() {
            return com.mojang.datafixers.util.Pair.of(InventoryMenu.BLOCK_ATLAS, ICONS[this.slot.getIndex()]);
        }
    }

    /** Player inventory: visible on the nest page and the accessories page (hotbar also on the squad page). */
    private class InventorySlot extends Slot {
        InventorySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            int tab = PuffMaidMenu.this.tab;
            return tab == TAB_NEST || tab == TAB_CURIOS || (tab == TAB_SQUAD && this.getContainerSlot() < 9);
        }
    }

    /**
     * Curio slot of one specific Curios slot type (ring, necklace, charm...) exactly like the player's own
     * Curios slots: only items that slot type's validators accept, and the type's empty-slot icon.
     */
    public class CurioSlot extends Slot {
        public final String type;
        /** Index of this slot within its slot type (Curios SlotContext index). */
        public final int typeIndex;
        private final int page;

        CurioSlot(Container container, int index, int x, int y, String type, int typeIndex, int page) {
            super(container, index, x, y);
            this.type = type;
            this.typeIndex = typeIndex;
            this.page = page;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return CuriosCompat.isValidFor(this.type, this.typeIndex, stack, PuffMaidMenu.this.puff,
                    PuffMaidMenu.this.player.level().isClientSide);
        }

        @Override
        @Nullable
        public com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> getNoItemIcon() {
            net.minecraft.resources.ResourceLocation icon = CuriosCompat.slotIcon(this.type);
            return icon == null ? null : com.mojang.datafixers.util.Pair.of(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, icon);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        /** Cursed / locked accessories stay on, exactly like in the player's Curios screen (creative may take them). */
        @Override
        public boolean mayPickup(Player player) {
            if (player.isCreative() || PuffMaidMenu.this.puff == null || player.level().isClientSide) return true;
            return CuriosCompat.canUnequip(PuffMaidMenu.this.puff, this.type, this.typeIndex, this.getItem());
        }

        @Override
        public boolean isActive() {
            return PuffMaidMenu.this.tab == TAB_CURIOS && PuffMaidMenu.this.curioPage == this.page;
        }
    }
}
