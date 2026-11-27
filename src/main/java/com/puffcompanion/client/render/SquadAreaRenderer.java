package com.puffcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffSquad;
import com.puffcompanion.item.LeaderCheeseStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * While the leader cheese staff is held: the squad's work area as a dashed sakura-pink box, the storage
 * container as an aqua box, and - after the first corner was picked - a live dashed preview from that corner to
 * the block under the crosshair (green = allowed size, red = too big).
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SquadAreaRenderer {
    private static final float DASH = 0.5F, GAP = 0.3F;

    private SquadAreaRenderer() {}

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        ItemStack staff = player.getMainHandItem().getItem() instanceof LeaderCheeseStaffItem ? player.getMainHandItem()
                : player.getOffhandItem().getItem() instanceof LeaderCheeseStaffItem ? player.getOffhandItem() : ItemStack.EMPTY;
        CompoundTag tag = staff.getTag();
        if (tag == null) return;

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        // dashes move slowly along the edges so the box reads as "selection"
        float phase = (player.tickCount + event.getPartialTick()) * 0.02F % (DASH + GAP);

        if (tag.contains("AreaMin")) {
            BlockPos lo = BlockPos.of(tag.getLong("AreaMin")), hi = BlockPos.of(tag.getLong("AreaMax"));
            box(ps, vc, lo.getX(), lo.getY(), lo.getZ(), hi.getX() + 1, hi.getY() + 1, hi.getZ() + 1,
                    0.95F, 0.45F, 0.65F, 1F, true, phase);
        }
        if (tag.contains("Storage")) {
            BlockPos s = BlockPos.of(tag.getLong("Storage"));
            box(ps, vc, s.getX() - 0.02, s.getY() - 0.02, s.getZ() - 0.02, s.getX() + 1.02, s.getY() + 1.02, s.getZ() + 1.02,
                    0.3F, 0.85F, 0.9F, 1F, false, 0);
        }
        if (tag.contains("Pos1") && LeaderCheeseStaffItem.mode(staff) == LeaderCheeseStaffItem.MODE_AREA) {
            BlockPos a = BlockPos.of(tag.getLong("Pos1"));
            box(ps, vc, a.getX(), a.getY(), a.getZ(), a.getX() + 1, a.getY() + 1, a.getZ() + 1, 1F, 0.85F, 0.3F, 1F, false, 0);
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos b = bh.getBlockPos();
                boolean ok = PuffSquad.fits(a, b);
                box(ps, vc, Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
                        ok ? 0.45F : 1F, ok ? 0.95F : 0.3F, ok ? 0.5F : 0.3F, 1F, true, phase);
            }
        }
        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void box(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a, boolean dashed, float phase) {
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            if (dashed) dashed(ps, vc, c[e[0]], c[e[1]], r, g, b, a, phase);
            else line(ps, vc, c[e[0]], c[e[1]], r, g, b, a);
        }
    }

    private static void dashed(PoseStack ps, VertexConsumer vc, double[] p, double[] q, float r, float g, float b, float a, float phase) {
        double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return;
        for (double t = phase - DASH - GAP; t < len; t += DASH + GAP) {
            double s0 = Math.max(0, t), s1 = Math.min(len, t + DASH);
            if (s1 <= s0) continue;
            line(ps, vc, new double[]{p[0] + dx * s0 / len, p[1] + dy * s0 / len, p[2] + dz * s0 / len},
                    new double[]{p[0] + dx * s1 / len, p[1] + dy * s1 / len, p[2] + dz * s1 / len}, r, g, b, a);
        }
    }

    private static void line(PoseStack ps, VertexConsumer vc, double[] p, double[] q, float r, float g, float b, float a) {
        Matrix4f pose = ps.last().pose();
        Matrix3f normal = ps.last().normal();
        float nx = (float) (q[0] - p[0]), ny = (float) (q[1] - p[1]), nz = (float) (q[2] - p[2]);
        float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= n;
        ny /= n;
        nz /= n;
        vc.vertex(pose, (float) p[0], (float) p[1], (float) p[2]).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
        vc.vertex(pose, (float) q[0], (float) q[1], (float) q[2]).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
    }
}
