package com.puffcompanion.compat.rats;

import com.github.alexthe666.rats.registry.RatsItemRegistry;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.function.Supplier;

/**
 * Jobs Puff can hand out to each squad rat. Each job names the Rats: Reboot work upgrades that speed it up
 * (registry fields verified with javap in {@code RatsItemRegistry}): a rat wearing one works twice as fast.
 */
public enum SquadJob {
    NONE("none", List.of()),
    LUMBERJACK("lumberjack", List.of(RatsItemRegistry.RAT_UPGRADE_LUMBERJACK)),
    MINER("miner", List.of(RatsItemRegistry.RAT_UPGRADE_QUARRY, RatsItemRegistry.RAT_UPGRADE_ORE_DOUBLING)),
    BUILDER("builder", List.of(RatsItemRegistry.RAT_UPGRADE_PLACER)),
    FARMER("farmer", List.of(RatsItemRegistry.RAT_UPGRADE_FARMER, RatsItemRegistry.RAT_UPGRADE_GARDENER)),
    FISHER("fisher", List.of(RatsItemRegistry.RAT_UPGRADE_FISHERMAN)),
    PATROL("patrol", List.of(RatsItemRegistry.RAT_UPGRADE_WARRIOR, RatsItemRegistry.RAT_UPGRADE_STRENGTH)),
    RANCHER("rancher", List.of(RatsItemRegistry.RAT_UPGRADE_BREEDER, RatsItemRegistry.RAT_UPGRADE_SHEARS));

    public final String key;
    private final List<? extends Supplier<Item>> boosts;

    SquadJob(String key, List<? extends Supplier<Item>> boosts) {
        this.key = key;
        this.boosts = boosts;
    }

    public String langKey() {
        return "puff.squad.job." + this.key;
    }

    public List<Item> boostItems() {
        return this.boosts.stream().map(Supplier::get).toList();
    }

    public static SquadJob byId(int id) {
        SquadJob[] v = values();
        return id >= 0 && id < v.length ? v[id] : NONE;
    }

    /** Next job in the panel's cycle; the builder is skipped without Create. */
    public SquadJob next(boolean createLoaded) {
        SquadJob j = this;
        do {
            j = byId((j.ordinal() + 1) % values().length);
        } while (j == BUILDER && !createLoaded);
        return j;
    }
}
