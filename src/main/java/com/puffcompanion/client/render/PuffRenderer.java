package com.puffcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.SpellCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffExpression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

public class PuffRenderer extends GeoEntityRenderer<PuffEntity> implements SkinnedBodyLayer.ModelPoseProvider {
    /**
     * DQS skin meshes live outside geo/ on purpose: GeckoLib parses every *.json under geo/ as a
     * Bedrock geometry file and logs an ERROR for each skin mesh.
     */
    public static final String SKIN_DIR = "skins/entity/";

    /** Always-visible base anatomy layers, drawn in this order. */
    public static final String[][] BASE_LAYERS = {
            {"mouse_girl_body", "mouse_girl_body"},
            {"mouse_girl_vroid_head", "mouse_girl_vroid_face"},
            {"mouse_girl_chiffon_hair", "mouse_girl_chiffon_hair"},
            {"mouse_girl_ears", "mouse_girl_ears"},
    };

    /** Outfit layers matched against {@link PuffEntity#getCurrentOutfit()}: {outfitId, skinBase, textureBase}. */
    public static final String[][] OUTFIT_LAYERS = {
            {"early", "mouse_girl_adventure", "mouse_girl_adventure"},
            {"mid", "mouse_girl_artisan", "mouse_girl_artisan"},
            {"late", "mouse_girl_starlight", "mouse_girl_starlight"},
            {"swim", "mouse_girl_bikini", "mouse_girl_bikini"},
            {"wedding", "mouse_girl_wedding", "mouse_girl_wedding"},
    };

    /** Set by the maid panel while it draws the 3D preview, to suppress world-only extras. */
    public static boolean guiPreview;

    private static final String RIGHT_HAND_BONE = "RightHandLocator";
    private static final String LEFT_HAND_BONE = "LeftHandLocator";

    /** PoseStack entry (entity-rotated) captured at the top-level bone, reused by the skin layers. */
    private final Matrix4f capturedPose = new Matrix4f();
    private final Matrix3f capturedNormal = new Matrix3f();
    private boolean poseCaptured;

    public PuffRenderer(EntityRendererProvider.Context context) {
        super(context, new PuffModel());
        this.shadowRadius = 0.4F;
        for (String[] layer : BASE_LAYERS) {
            // Face gets pure-up normals: no eye-socket / chin shadows at all
            boolean isHead = "mouse_girl_vroid_head".equals(layer[0]);
            boolean isEars = "mouse_girl_ears".equals(layer[0]);
            float upBlend = isHead ? 1.0F : SkinnedBodyLayer.DEFAULT_NORMAL_UP_BLEND;
            SkinnedBodyLayer<PuffEntity> bodyLayer = new SkinnedBodyLayer<>(this,
                    new ResourceLocation(PuffCompanion.MODID, SKIN_DIR + layer[0] + ".skin.bin"),
                    new ResourceLocation(PuffCompanion.MODID, "textures/entity/" + layer[1] + ".png"),
                    upBlend);
            if (isHead) {
                bodyLayer.withDynamicTexture(e -> PuffExpression.faceTexture(e.resolveRenderExpression()))
                        .withMorphs(new ResourceLocation(PuffCompanion.MODID, SKIN_DIR + "mouse_girl_vroid_head.morphs.bin"),
                                PuffEntity::resolveRenderExpression);
            } else if (isEars) {
                bodyLayer.withMorphs(new ResourceLocation(PuffCompanion.MODID, SKIN_DIR + "mouse_girl_ears.morphs.bin"),
                        PuffEntity::resolveRenderEar);
            }
            addRenderLayer(bodyLayer);
        }
        for (String[] outfit : OUTFIT_LAYERS) {
            final String outfitId = outfit[0];
            addRenderLayer(new SkinnedBodyLayer<>(this,
                    new ResourceLocation(PuffCompanion.MODID, SKIN_DIR + outfit[1] + ".skin.bin"),
                    new ResourceLocation(PuffCompanion.MODID, "textures/entity/" + outfit[2] + ".png"),
                    SkinnedBodyLayer.DEFAULT_NORMAL_UP_BLEND,
                    e -> outfitId.equals(resolveOutfitId(e))));
        }
        addRenderLayer(new GeoRenderLayer<>(this) {
            @Override
            public void render(PoseStack poseStack, PuffEntity animatable, BakedGeoModel bakedModel,
                               RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                               float partialTick, int packedLight, int packedOverlay) {
                if (!poseCaptured) return;
                if (!guiPreview && !animatable.shouldShowHeldItems()) return;
                ItemStack main = resolveRightHandStack(animatable);
                if (!main.isEmpty()) {
                    bakedModel.getBone(RIGHT_HAND_BONE).ifPresent(bone ->
                            renderHandItem(poseStack, capturedPose, bone.getModelSpaceMatrix(), main, animatable, bufferSource, packedLight, false));
                }
                ItemStack off = animatable.getOffhandItem();
                if (!off.isEmpty()) {
                    bakedModel.getBone(LEFT_HAND_BONE).ifPresent(bone ->
                            renderHandItem(poseStack, capturedPose, bone.getModelSpaceMatrix(), off, animatable, bufferSource, packedLight, true));
                }
            }
        });
    }

    private static ItemStack resolveRightHandStack(PuffEntity animatable) {
        ItemStack main = animatable.getMainHandItem();
        if (SpellCompat.isCastingStaffOrWand(main)) return main;
        ItemStack cast = animatable.getCastDisplayStack();
        return !cast.isEmpty() ? cast : main;
    }

    private static void renderHandItem(PoseStack poseStack, Matrix4f rootPose, Matrix4f boneSpacePose,
                                       ItemStack stack, PuffEntity animatable, MultiBufferSource bufferSource,
                                       int packedLight, boolean leftHand) {
        poseStack.pushPose();
        poseStack.last().pose().set(rootPose).mul(boneSpacePose);
        // Verified on http://127.0.0.1:8766/ (held-item-site-verify.png):
        // shift down along fingers (-1.35/16), slightly inward into palm (+0.35/16 right, -0.35/16 left), slightly forward (-0.30/16)
        poseStack.translate(leftHand ? -0.35F / 16.0F : 0.35F / 16.0F, -1.35F / 16.0F, -0.30F / 16.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-85.0F));
        float scale = stack.getItem() instanceof ShieldItem ? 0.68F : 0.72F;
        poseStack.scale(scale, scale, scale);
        ItemDisplayContext ctx = leftHand ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        Minecraft.getInstance().getItemRenderer().renderStatic(animatable, stack, ctx, leftHand,
                poseStack, bufferSource, animatable.level(), packedLight, OverlayTexture.NO_OVERLAY, animatable.getId());
        poseStack.popPose();
    }

    private static String resolveOutfitId(PuffEntity e) {
        String id = e.getCurrentOutfit();
        for (String[] outfit : OUTFIT_LAYERS) {
            if (outfit[0].equals(id)) return id;
        }
        return "early";
    }

    @Override
    public void render(PuffEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        if (guiPreview) return;   // 3D preview in the maid panel: no speech bubble
        PuffBubbleRenderer.render(entity, partialTick, poseStack, bufferSource, this.entityRenderDispatcher,
                PuffBubbleRenderer.font());
    }

    @Override
    public void preRender(PoseStack poseStack, PuffEntity animatable, BakedGeoModel model, MultiBufferSource bufferSource,
                          VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        if (!isReRender) {
            this.poseCaptured = false;
            model.getBone(RIGHT_HAND_BONE).ifPresent(b -> b.setTrackingMatrices(true));
            model.getBone(LEFT_HAND_BONE).ifPresent(b -> b.setTrackingMatrices(true));
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
        if (!isReRender && animatable.isPassenger()) {
            // Chibi size while sitting on the owner's shoulder (skin layers reuse this pose via capture)
            poseStack.scale(PuffEntity.SHOULDER_SCALE, PuffEntity.SHOULDER_SCALE, PuffEntity.SHOULDER_SCALE);
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, PuffEntity animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        if (!isReRender && bone.getParent() == null) {
            // Same frame GeckoLib stores as modelRenderTranslations: after entity rotations, before bone transforms
            PoseStack.Pose last = poseStack.last();
            this.capturedPose.set(last.pose());
            this.capturedNormal.set(last.normal());
            this.poseCaptured = true;
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    /**
     * Resting: the YSM "sleep" clip only closes the eyes (vanilla rotates sleeping players itself), so lay
     * the whole model on her back here, in the entity-rotated frame, centred on the hitbox.
     */
    @Override
    protected void applyRotations(PuffEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw,
                                  float partialTick) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick);
        if (animatable.isResting() && !animatable.isPassenger()) {
            poseStack.translate(0.0, 0.14, -0.85);
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90.0F));
        }
    }

    /** The base skeleton is bones only; appearance comes entirely from the SkinnedBodyLayers. */
    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight,
                                  int packedOverlay, float red, float green, float blue, float alpha) {
    }

    @Nullable
    @Override
    public Matrix4f getModelRenderPose() {
        return this.poseCaptured ? this.capturedPose : null;
    }

    @Override
    public Matrix3f getModelRenderNormal() {
        return this.capturedNormal;
    }
}
