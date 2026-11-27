package com.puffcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Compact speech bubble beside Puff's head (right ear side): creamy-white rounded card with
 * sakura-pink border, tiny mouse-ear accents, a left-pointing tail, and warm cocoa text.
 */
public final class PuffBubbleRenderer {
    private static final int MAX_LINE_WIDTH = 132;
    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int TAIL = 5;
    private static final int SIDE_OFFSET = 26;       // clear of Puff's ear & hair
    private static final float SCALE = 0.0155F;      // compact scale beside head
    private static final double MAX_DISTANCE_SQR = 32.0 * 32.0;

    private static final int RGB_SHADOW = 0x2B1922;
    private static final int RGB_BORDER = 0xF2A0B8;
    private static final int RGB_BG = 0xFFFDF8;
    private static final int RGB_HIGHLIGHT = 0xFFFFFF;
    private static final int RGB_BOTTOM_TINT = 0xFCE8EE;
    private static final int RGB_EAR_INNER = 0xFAD2DF;
    private static final int RGB_TEXT = 0x4A332D;

    private PuffBubbleRenderer() {}

    public static void render(PuffEntity puff, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                              EntityRenderDispatcher dispatcher, Font font) {
        if (Minecraft.getInstance().screen instanceof com.puffcompanion.client.gui.PuffDialogueScreen) return;
        String text = puff.getBubbleText();
        if (text == null) return;
        if (dispatcher.distanceToSqr(puff) > MAX_DISTANCE_SQR) return;

        float alpha = puff.getBubbleAlpha(partialTick);
        if (alpha <= 0.02F) return;

        List<FormattedCharSequence> lines = font.split(FormattedText.of(text), MAX_LINE_WIDTH);
        if (lines.isEmpty()) return;
        int textWidth = 0;
        for (FormattedCharSequence line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }

        int boxW = Math.max(38, textWidth + PAD_X * 2);
        int boxH = lines.size() * LINE_HEIGHT + PAD_Y * 2 - 2;
        int left = SIDE_OFFSET + TAIL;
        int right = left + boxW;
        int top = -boxH / 2 - 4;
        int bottom = top + boxH;
        int tailY = Mth.clamp((top + bottom) / 2 + 1, top + 4, bottom - 4);

        double modelHeight = puff.getBbHeight() * (puff.isPassenger() ? PuffEntity.SHOULDER_SCALE : 1.0F);
        double y = modelHeight * 0.85;

        poseStack.pushPose();
        poseStack.translate(0.0, y, 0.0);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(-SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();
        int light = LightTexture.FULL_BRIGHT;

        VertexConsumer vc = buffer.getBuffer(RenderType.textBackground());

        // 1. Soft drop shadow (z = 0.024F)
        int shadow = argb(0.24F * alpha, RGB_SHADOW);
        drawRoundedBox(vc, matrix, left + 1, top + 1.5F, right + 1, bottom + 1.5F, shadow, light, 0.024F);

        // 2. Sakura-pink rounded border + tiny mouse-ear silhouette + left-pointing tail (z = 0.018F)
        int border = argb(0.96F * alpha, RGB_BORDER);
        drawRoundedBox(vc, matrix, left - 1, top - 1, right + 1, bottom + 1, border, light, 0.018F);
        drawMouseEars(vc, matrix, (left + right) / 2, top, border, argb(0.94F * alpha, RGB_EAR_INNER), light);

        // Left-pointing tail border
        quad(vc, matrix, left - 2, tailY - 3, left, tailY + 3, border, light, 0.018F);
        quad(vc, matrix, left - 3, tailY - 2, left - 2, tailY + 2, border, light, 0.018F);
        quad(vc, matrix, left - 4, tailY - 1, left - 3, tailY + 1, border, light, 0.018F);
        quad(vc, matrix, left - 5, tailY, left - 4, tailY + 1, border, light, 0.018F);

        // 3. Creamy-white main card body (z = 0.012F)
        int bg = argb(0.95F * alpha, RGB_BG);
        drawRoundedBox(vc, matrix, left, top, right, bottom, bg, light, 0.012F);

        // Left-pointing tail fill
        quad(vc, matrix, left - 1, tailY - 2, left + 1, tailY + 2, bg, light, 0.012F);
        quad(vc, matrix, left - 2, tailY - 1, left - 1, tailY + 1, bg, light, 0.012F);
        quad(vc, matrix, left - 3, tailY, left - 2, tailY + 1, bg, light, 0.012F);

        // 4. Inner highlight & soft bottom sakura tint (z = 0.008F)
        int highlight = argb(0.85F * alpha, RGB_HIGHLIGHT);
        int bottomTint = argb(0.80F * alpha, RGB_BOTTOM_TINT);
        quad(vc, matrix, left + 2, top, right - 2, top + 1, highlight, light, 0.008F);
        quad(vc, matrix, left + 2, bottom - 1.5F, right - 2, bottom, bottomTint, light, 0.008F);

        // 5. Warm cocoa text (left-aligned inside bubble, z = 0.0F)
        int textColor = argb(Math.max(alpha, 0.08F), RGB_TEXT);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float ly = top + PAD_Y + i * LINE_HEIGHT;
            font.drawInBatch(line, left + PAD_X, ly, textColor, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
        }

        poseStack.popPose();
    }

    private static void drawRoundedBox(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1,
                                       int argb, int light, float z) {
        quad(vc, m, x0 + 2, y0, x1 - 2, y1, argb, light, z);
        quad(vc, m, x0 + 1, y0 + 1, x0 + 2, y1 - 1, argb, light, z);
        quad(vc, m, x0, y0 + 2, x0 + 1, y1 - 2, argb, light, z);
        quad(vc, m, x1 - 2, y0 + 1, x1 - 1, y1 - 1, argb, light, z);
        quad(vc, m, x1 - 1, y0 + 2, x1, y1 - 2, argb, light, z);
    }

    private static void drawMouseEars(VertexConsumer vc, Matrix4f m, int midX, int top,
                                      int outerCol, int innerCol, int light) {
        int[] offsets = {-8, 8};
        for (int ox : offsets) {
            int cx = midX + ox;
            quad(vc, m, cx - 3, top - 3, cx + 3, top - 1, outerCol, light, 0.018F);
            quad(vc, m, cx - 2, top - 4, cx + 2, top - 3, outerCol, light, 0.018F);
            quad(vc, m, cx - 1.5F, top - 2.5F, cx + 1.5F, top - 1, innerCol, light, 0.012F);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1,
                             int argb, int light, float z) {
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        vc.vertex(m, x0, y1, z).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x1, y1, z).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x1, y0, z).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x0, y0, z).color(r, g, b, a).uv2(light).endVertex();
    }

    private static int argb(float alpha, int rgb) {
        return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }
}
