package com.puffcompanion.compat.create;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.CreateCompat;
import com.puffcompanion.entity.PuffSquad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deployed Create schematic turned into an ordered block list (bottom layer first). Create stores uploaded
 * schematics as gzipped vanilla structure templates under {@code schematics/uploaded/<owner>/<file>}; the item
 * NBT gives the anchor, rotation and mirror the schematic table / cannon would use. Only air (or replaceable)
 * spots are built; blocks already there are never broken.
 */
public final class SchematicPlan {
    public record Step(int index, BlockPos pos, BlockState state) {}

    private static final Map<String, SchematicPlan> CACHE = new HashMap<>();

    private final List<BlockPos> positions;
    private final List<BlockState> states;

    private SchematicPlan(List<BlockPos> positions, List<BlockState> states) {
        this.positions = positions;
        this.states = states;
    }

    public int size() {
        return this.positions.size();
    }

    /** Plan of the squad's blueprint, or null (no / undeployed / unreadable schematic, or no Create). */
    @Nullable
    public static SchematicPlan of(ServerLevel level, PuffSquad squad) {
        ItemStack stack = squad.blueprint.getItem(0);
        if (!CreateCompat.isSchematic(stack) || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (!tag.getBoolean("Deployed") || !tag.contains("Anchor")) return null;
        String owner = tag.getString("Owner"), file = tag.getString("File");
        BlockPos anchor = NbtUtils.readBlockPos(tag.getCompound("Anchor"));
        Rotation rotation = enumOr(Rotation.class, tag.getString("Rotation"), Rotation.NONE);
        Mirror mirror = enumOr(Mirror.class, tag.getString("Mirror"), Mirror.NONE);
        String key = owner + "/" + file + "@" + anchor.toShortString() + "/" + rotation + "/" + mirror;
        if (!key.equals(squad.buildKey)) {
            squad.buildKey = key;
            squad.buildIndex = 0;
        }
        if (CACHE.containsKey(key)) return CACHE.get(key);
        SchematicPlan plan = load(level, owner, file, anchor, rotation, mirror);
        CACHE.put(key, plan);   // null cached too: no re-reading a broken file every tick
        return plan;
    }

    /** Forget cached plans (e.g. the file was re-uploaded): the next {@link #of} reads it again. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Nullable
    private static SchematicPlan load(ServerLevel level, String owner, String file, BlockPos anchor, Rotation rotation, Mirror mirror) {
        Path root = FMLPaths.GAMEDIR.get().resolve("schematics").normalize();
        Path uploaded = root.resolve("uploaded").resolve(owner).resolve(file).normalize();
        Path local = root.resolve(file).normalize();
        Path path = Files.isRegularFile(uploaded) ? uploaded : local;
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            PuffCompanion.LOGGER.warn("[Puff Companion] Blueprint file not found: {}", path);
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            CompoundTag nbt = NbtIo.readCompressed(in);
            StructureTemplate template = new StructureTemplate();
            template.load(level.holderLookup(Registries.BLOCK), nbt);
            if (template.palettes.isEmpty()) return null;
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror);
            record Entry(BlockPos pos, BlockState state, int order) {}
            List<Entry> entries = new ArrayList<>();
            int order = 0;
            for (StructureTemplate.StructureBlockInfo info : template.palettes.get(0).blocks()) {
                BlockState state = info.state();
                if (state.isAir() || state.is(Blocks.STRUCTURE_VOID) || state.getBlock().asItem() == Items.AIR) continue;
                BlockPos pos = StructureTemplate.calculateRelativePosition(settings, info.pos()).offset(anchor);
                entries.add(new Entry(pos, state.mirror(mirror).rotate(rotation), order++));
            }
            entries.sort(Comparator.comparingInt((Entry e) -> e.pos().getY()).thenComparingInt(Entry::order));
            List<BlockPos> positions = new ArrayList<>();
            List<BlockState> states = new ArrayList<>();
            for (Entry e : entries) {
                positions.add(e.pos());
                states.add(e.state());
            }
            return new SchematicPlan(positions, states);
        } catch (Exception e) {
            PuffCompanion.LOGGER.warn("[Puff Companion] Could not read blueprint {}: {}", path, e.toString());
            return null;
        }
    }

    /**
     * Next block still to place, from the squad's progress on. Spots already built (or occupied by something
     * else, which is never broken) are skipped and the progress moves past them. Null when everything is done.
     */
    @Nullable
    public Step next(ServerLevel level, PuffSquad squad) {
        for (int i = Math.max(0, squad.buildIndex); i < this.positions.size(); i++) {
            BlockPos pos = this.positions.get(i);
            if (!level.isLoaded(pos)) return null;
            BlockState now = level.getBlockState(pos);
            BlockState want = this.states.get(i);
            if (now.equals(want) || !now.canBeReplaced()) {
                if (i == squad.buildIndex) squad.buildIndex = i + 1;
                continue;
            }
            return new Step(i, pos, want);
        }
        return null;
    }
}
