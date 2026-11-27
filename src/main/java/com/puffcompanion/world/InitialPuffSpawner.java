package com.puffcompanion.world;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Genesis first meeting": the first time a player moves around in a save, one Puff pops out of the
 * grass 3~5 blocks away and is bound to that player. {@link PuffWorldData} guarantees once per save.
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InitialPuffSpawner {
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final double MOVE_THRESHOLD_SQR = 1.0;
    private static final int MIN_RADIUS = 3;
    private static final int MAX_RADIUS = 5;
    private static final int TRIES_PER_CHECK = 24;
    /** After this many failed checks (~30 s) accept any solid ground instead of grass only. */
    private static final int GRASS_ONLY_CHECKS = 60;

    /** Transient (not saved): where each player stood when first seen this session, and failed checks. */
    private static final Map<UUID, Vec3> START_POS = new HashMap<>();
    private static final Map<UUID, Integer> FAILED_CHECKS = new HashMap<>();

    private InitialPuffSpawner() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            START_POS.put(player.getUUID(), player.position());
            FAILED_CHECKS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        START_POS.remove(event.getEntity().getUUID());
        FAILED_CHECKS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        START_POS.clear();
        FAILED_CHECKS.clear();
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side.isClient()) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0 || player.isSpectator()) return;

        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return;

        PuffWorldData data = PuffWorldData.get(player.server);
        if (data.isInitialPuffSpawned()) {
            START_POS.remove(player.getUUID());
            return;
        }

        // Wait until the player has actually moved after joining
        Vec3 start = START_POS.computeIfAbsent(player.getUUID(), id -> player.position());
        Vec3 now = player.position();
        double dx = now.x - start.x, dz = now.z - start.z;
        if (dx * dx + dz * dz < MOVE_THRESHOLD_SQR) return;

        int failed = FAILED_CHECKS.getOrDefault(player.getUUID(), 0);
        BlockPos spawnPos = findSpawnPos(level, player, failed < GRASS_ONLY_CHECKS);
        if (spawnPos == null) {
            FAILED_CHECKS.put(player.getUUID(), failed + 1);
            return;
        }

        PuffEntity puff = ModEntities.PUFF.get().create(level);
        if (puff == null) return;
        double x = spawnPos.getX() + 0.5, y = spawnPos.getY(), z = spawnPos.getZ() + 0.5;
        float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * Mth.RAD_TO_DEG) - 90.0F;
        puff.moveTo(x, y, z, yaw, 0.0F);
        puff.setYHeadRot(yaw);
        puff.setYBodyRot(yaw);
        puff.bindOwner(player);
        if (!level.addFreshEntity(puff)) return;

        data.markInitialPuffSpawned(puff.getUUID(), player.getUUID());
        START_POS.remove(player.getUUID());
        FAILED_CHECKS.remove(player.getUUID());
        player.displayClientMessage(Component.translatable("message.puffcompanion.first_meet", puff.getDisplayName()), false);
        PuffCompanion.LOGGER.info("[Puff Companion] Initial Puff {} spawned at {} for {}",
                puff.getUUID(), spawnPos, player.getGameProfile().getName());
    }

    /** Random spot 3~5 blocks from the player, standing on grass (or any sturdy ground if allowed). */
    @Nullable
    private static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player, boolean grassOnly) {
        RandomSource random = player.getRandom();
        for (int i = 0; i < TRIES_PER_CHECK; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
            int x = Mth.floor(player.getX() + Math.cos(angle) * radius);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * radius);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - player.getY()) > 4) continue;
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.below();
            BlockState groundState = level.getBlockState(ground);

            boolean okGround = grassOnly
                    ? groundState.is(Blocks.GRASS_BLOCK)
                    : groundState.isFaceSturdy(level, ground, Direction.UP) && groundState.getFluidState().isEmpty();
            if (!okGround) continue;
            if (!level.getFluidState(feet).isEmpty()) continue;
            if (!level.noCollision(ModEntities.PUFF.get().getAABB(x + 0.5, y, z + 0.5))) continue;
            return feet;
        }
        return null;
    }
}
