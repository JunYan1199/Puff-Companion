package com.puffcompanion.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puff's rat squad (stage 6), saved on Puff: member rats and each one's job, the work area (two corners), the
 * supply / storage container, the Create blueprint slot with its build progress, and her Lootr state.
 * Plain Minecraft types only - no Rats / Create / Lootr classes, so it loads in any pack.
 */
public class PuffSquad {
    public static final int MAX_MEMBERS = 16;
    public static final int MAX_X = 32, MAX_Y = 16, MAX_Z = 32;

    /** One squad rat: UUID, cached name (shown while it is unloaded) and job ordinal. */
    public static final class Member {
        public final UUID id;
        public String name;
        public int job;

        Member(UUID id, String name, int job) {
            this.id = id;
            this.name = name;
            this.job = job;
        }
    }

    private final Map<UUID, Member> members = new LinkedHashMap<>();
    @Nullable private BlockPos areaA, areaB;
    @Nullable private BlockPos storage;
    /** Create schematic item (one slot). */
    public final SimpleContainer blueprint = new SimpleContainer(1);
    /** Index into the blueprint's block list the builders have checked up to. */
    public int buildIndex;
    /** Blueprint identity the progress belongs to (file + anchor); progress resets when it changes. */
    public String buildKey = "";
    /** Short status of the build ("missing: oak planks" ...) for the panel; not saved. */
    public String buildStatus = "";
    public boolean autoLootr = true;
    /** Lootr containers Puff already opened (BlockPos longs, current dimension only). */
    public final Set<Long> lootrOpened = new HashSet<>();
    /** Lootr target Puff is walking to (not saved). */
    @Nullable public BlockPos lootrTarget;
    public int lootrTicks;

    // ---------------------------------------------------------------- members

    public List<Member> members() {
        return new ArrayList<>(this.members.values());
    }

    @Nullable
    public Member member(UUID id) {
        return this.members.get(id);
    }

    public boolean isMember(UUID id) {
        return this.members.containsKey(id);
    }

    /** False when the squad is full or the rat is already in it. */
    public boolean add(UUID id, String name) {
        if (this.members.containsKey(id) || this.members.size() >= MAX_MEMBERS) return false;
        this.members.put(id, new Member(id, name, 0));
        return true;
    }

    public boolean remove(UUID id) {
        return this.members.remove(id) != null;
    }

    public int size() {
        return this.members.size();
    }

    // ---------------------------------------------------------------- area / storage

    /** Sets the work area; false (unchanged) if it is bigger than 32 x 16 x 32. */
    public boolean setArea(BlockPos a, BlockPos b) {
        if (!fits(a, b)) return false;
        this.areaA = a.immutable();
        this.areaB = b.immutable();
        return true;
    }

    public static boolean fits(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + 1 <= MAX_X && Math.abs(a.getY() - b.getY()) + 1 <= MAX_Y
                && Math.abs(a.getZ() - b.getZ()) + 1 <= MAX_Z;
    }

    public boolean hasArea() {
        return this.areaA != null && this.areaB != null;
    }

    @Nullable
    public BlockPos areaMin() {
        return this.hasArea() ? new BlockPos(Math.min(this.areaA.getX(), this.areaB.getX()),
                Math.min(this.areaA.getY(), this.areaB.getY()), Math.min(this.areaA.getZ(), this.areaB.getZ())) : null;
    }

    @Nullable
    public BlockPos areaMax() {
        return this.hasArea() ? new BlockPos(Math.max(this.areaA.getX(), this.areaB.getX()),
                Math.max(this.areaA.getY(), this.areaB.getY()), Math.max(this.areaA.getZ(), this.areaB.getZ())) : null;
    }

    /** Work area as a box (whole blocks), or null. */
    @Nullable
    public AABB areaBox() {
        BlockPos lo = this.areaMin(), hi = this.areaMax();
        return lo == null ? null : new AABB(lo.getX(), lo.getY(), lo.getZ(), hi.getX() + 1, hi.getY() + 1, hi.getZ() + 1);
    }

    public boolean inArea(BlockPos p) {
        BlockPos lo = this.areaMin(), hi = this.areaMax();
        return lo != null && p.getX() >= lo.getX() && p.getX() <= hi.getX() && p.getY() >= lo.getY() && p.getY() <= hi.getY()
                && p.getZ() >= lo.getZ() && p.getZ() <= hi.getZ();
    }

    @Nullable
    public BlockPos storage() {
        return this.storage;
    }

    public void setStorage(@Nullable BlockPos pos) {
        this.storage = pos == null ? null : pos.immutable();
    }

    // ---------------------------------------------------------------- NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Member m : this.members.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", m.id);
            t.putString("Name", m.name);
            t.putInt("Job", m.job);
            list.add(t);
        }
        tag.put("Members", list);
        if (this.areaA != null && this.areaB != null) {
            tag.put("AreaA", NbtUtils.writeBlockPos(this.areaA));
            tag.put("AreaB", NbtUtils.writeBlockPos(this.areaB));
        }
        if (this.storage != null) tag.put("Storage", NbtUtils.writeBlockPos(this.storage));
        tag.put("Blueprint", this.blueprint.getItem(0).save(new CompoundTag()));
        tag.putInt("BuildIndex", this.buildIndex);
        tag.putString("BuildKey", this.buildKey);
        tag.putBoolean("AutoLootr", this.autoLootr);
        ListTag opened = new ListTag();
        for (long l : this.lootrOpened) opened.add(LongTag.valueOf(l));
        tag.put("LootrOpened", opened);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.members.clear();
        ListTag list = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            if (!t.hasUUID("Id")) continue;
            this.members.put(t.getUUID("Id"), new Member(t.getUUID("Id"), t.getString("Name"), t.getInt("Job")));
        }
        this.areaA = tag.contains("AreaA") ? NbtUtils.readBlockPos(tag.getCompound("AreaA")) : null;
        this.areaB = tag.contains("AreaB") ? NbtUtils.readBlockPos(tag.getCompound("AreaB")) : null;
        this.storage = tag.contains("Storage") ? NbtUtils.readBlockPos(tag.getCompound("Storage")) : null;
        this.blueprint.setItem(0, net.minecraft.world.item.ItemStack.of(tag.getCompound("Blueprint")));
        this.buildIndex = tag.getInt("BuildIndex");
        this.buildKey = tag.getString("BuildKey");
        this.autoLootr = !tag.contains("AutoLootr") || tag.getBoolean("AutoLootr");
        this.lootrOpened.clear();
        ListTag opened = tag.getList("LootrOpened", Tag.TAG_LONG);
        for (int i = 0; i < opened.size(); i++) this.lootrOpened.add(((LongTag) opened.get(i)).getAsLong());
    }
}
