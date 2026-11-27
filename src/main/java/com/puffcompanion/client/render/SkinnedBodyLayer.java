package com.puffcompanion.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Drop-in GeckoLib 4 RenderLayer that renders a continuous, smooth-deforming
 * Dual-Quaternion Skinned (DQS) mesh driven directly by GeckoLib's live GeoBone poses.
 *
 * <p>Ported from the original Rats MouseApothecary prototype. Integration fixes for GeckoLib 4.8.x:
 * <ul>
 *   <li>Bone {@code modelSpaceMatrix} is only written when {@link GeoBone#isTrackingMatrices()} is true,
 *       so tracking is enabled for every skinned bone in {@link #preRender}.</li>
 *   <li>{@code modelSpaceMatrix} is captured after translateToPivot+rotate but before translateAwayFromPivot,
 *       so the rest inverse is {@code T(-pivot)} instead of identity.</li>
 *   <li>GeckoLib calls {@code applyRenderLayers} after {@code actuallyRender} has popped its entity rotation,
 *       so the renderer can supply its rotated model pose via {@link ModelPoseProvider}.</li>
 * </ul>
 */
public final class SkinnedBodyLayer<T extends GeoEntity> extends GeoRenderLayer<T> {
    private static final float BLOCK_SCALE = 1.0F / 16.0F;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Default anime "flat light" weight: final normal = normalize(blend * up + (1 - blend) * skinnedNormal).
     * 0 = raw normals (vanilla directional shading), 1 = every vertex lit like an upward face.
     * packedLight is untouched, so block/sky light (night, caves) still darkens the model.
     */
    public static final float DEFAULT_NORMAL_UP_BLEND = 0.85F;

    /**
     * Implemented by renderers that capture the entity-rotated PoseStack entry GeckoLib used for bone
     * rendering this frame (GeckoLib pops it before render layers run).
     */
    public interface ModelPoseProvider {
        /** @return captured pose matrix, or null if nothing was captured this frame */
        @org.jetbrains.annotations.Nullable Matrix4f getModelRenderPose();
        Matrix3f getModelRenderNormal();
    }

    private final ResourceLocation skinJsonLoc;
    private final ResourceLocation textureLoc;
    private SkinData skin;
    private float[] deformedPos;
    private float[] deformedNorm;
    private float[] transformedPos;
    private float[] transformedNorm;
    private float[][] dualQuats;
    private float[][] boneMats;
    private float[] lastBoneMats;
    private int lastEntityId = Integer.MIN_VALUE;
    private long lastSkinnedNanos = 0L;
    private boolean hasCachedSkin;
    private BakedGeoModel cachedBoneModel;
    private GeoBone[] cachedBones;
    private VertexBuffer cachedVbo;
    private BufferBuilder vboBuilder;
    private boolean vboDirty = true;
    private int lastVboLight = Integer.MIN_VALUE;
    private int lastVboOverlay = Integer.MIN_VALUE;
    private final Matrix4f delta = new Matrix4f();
    private final Matrix4f restInv = new Matrix4f();
    private final Matrix4f vboModelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final float normalUpBlend;
    private final boolean pureUpNormals;
    private final Predicate<T> visibleWhen;
    private boolean orientationChecked;
    private boolean normMatChecked;
    private Function<T, ResourceLocation> textureProvider;
    private ResourceLocation morphBinLoc;
    private Function<T, String> morphProvider;
    private Map<String, MorphTarget> morphTargets;
    private float[] morphedVerts;
    private String lastMorphName = "";

    private static boolean shaderApiChecked;
    private static Object irisApiInstance;
    private static java.lang.reflect.Method irisShaderActiveMethod;

    private static boolean isShaderPackInUse() {
        if (!shaderApiChecked) {
            shaderApiChecked = true;
            String[] candidates = {
                    "net.irisshaders.iris.api.v0.IrisApi",
                    "net.coderbot.iris.api.v0.IrisApi"
            };
            for (String clsName : candidates) {
                try {
                    Class<?> cls = Class.forName(clsName);
                    irisApiInstance = cls.getMethod("getInstance").invoke(null);
                    irisShaderActiveMethod = cls.getMethod("isShaderPackInUse");
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (irisApiInstance != null && irisShaderActiveMethod != null) {
            try {
                return Boolean.TRUE.equals(irisShaderActiveMethod.invoke(irisApiInstance));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public SkinnedBodyLayer(GeoRenderer<T> renderer, ResourceLocation skinJsonLoc, ResourceLocation textureLoc) {
        this(renderer, skinJsonLoc, textureLoc, DEFAULT_NORMAL_UP_BLEND, anim -> true);
    }

    public SkinnedBodyLayer(GeoRenderer<T> renderer, ResourceLocation skinJsonLoc, ResourceLocation textureLoc,
                            float normalUpBlend) {
        this(renderer, skinJsonLoc, textureLoc, normalUpBlend, anim -> true);
    }

    public SkinnedBodyLayer(GeoRenderer<T> renderer, ResourceLocation skinJsonLoc, ResourceLocation textureLoc,
                            float normalUpBlend, Predicate<T> visibleWhen) {
        super(renderer);
        this.skinJsonLoc = skinJsonLoc;
        this.textureLoc = textureLoc;
        this.normalUpBlend = Math.max(0.0F, Math.min(1.0F, normalUpBlend));
        this.pureUpNormals = this.normalUpBlend >= 0.999F;
        this.visibleWhen = visibleWhen != null ? visibleWhen : anim -> true;
    }

    /** Optional dynamic texture selector (e.g. 24 facial expression textures). */
    public SkinnedBodyLayer<T> withDynamicTexture(Function<T, ResourceLocation> provider) {
        this.textureProvider = provider;
        return this;
    }

    /** Optional sparse vertex morph targets loaded from a PMRP binary file (expressions / ear shapes). */
    public SkinnedBodyLayer<T> withMorphs(ResourceLocation morphBinLoc, Function<T, String> provider) {
        this.morphBinLoc = morphBinLoc;
        this.morphProvider = provider;
        return this;
    }

    /**
     * Some exported meshes (body, bikini) have inward-facing normals, which makes upward surfaces
     * (collarbone, chest, chin) shade dark. Vote per vertex: does the normal point away from the pivot of the
     * vertex's dominant bone? If most do not, the whole mesh is flipped. Bone pivots come from the baked
     * GeckoLib model, which uses the same mirrored-X convention as the converted vertices.
     */
    private void fixNormalOrientation(BakedGeoModel bakedModel) {
        if (pureUpNormals) return;
        float[][] pivots = new float[skin.activeBones.length][];
        for (int b = 0; b < skin.activeBones.length; b++) {
            final int idx = b;
            bakedModel.getBone(skin.activeBones[b]).ifPresent(bone -> pivots[idx] = new float[]{
                    bone.getPivotX() * BLOCK_SCALE, bone.getPivotY() * BLOCK_SCALE, bone.getPivotZ() * BLOCK_SCALE});
        }
        int outward = 0, counted = 0;
        int nV = skin.verts.length / 3;
        for (int i = 0; i < nV; i++) {
            int bi = i * 4, best = bi;
            for (int k = 1; k < 4; k++) if (skin.boneW[bi + k] > skin.boneW[best]) best = bi + k;
            float[] p = pivots[skin.boneIdx[best]];
            if (p == null) continue;
            int vi = i * 3;
            float dot = (skin.verts[vi] - p[0]) * skin.norms[vi]
                    + (skin.verts[vi + 1] - p[1]) * skin.norms[vi + 1]
                    + (skin.verts[vi + 2] - p[2]) * skin.norms[vi + 2];
            counted++;
            if (dot > 0.0F) outward++;
        }
        boolean flip = counted > 0 && outward * 2 < counted;
        if (flip) {
            for (int i = 0; i < skin.norms.length; i++) skin.norms[i] = -skin.norms[i];
        }
        LOGGER.info("[Puff Companion] {}: {}/{} normals outward -> {}", skinJsonLoc, outward, counted,
                flip ? "flipped (mesh normals were inward)" : "kept");
    }

    private void ensureLoaded() {
        if (skin != null) return;
        try (var res = Minecraft.getInstance().getResourceManager().open(skinJsonLoc)) {
            String[] bones;
            float[] verts, norms, uvs, bW;
            int[] quads, quadUvs, bIdx;

            if (skinJsonLoc.getPath().endsWith(".skin.bin")) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(res, 65536));
                byte[] magic = new byte[4];
                in.readFully(magic);
                if (magic[0] != 'P' || magic[1] != 'S' || magic[2] != 'K' || magic[3] != 'N') {
                    throw new IllegalStateException("Invalid skin binary header");
                }
                int version = in.readInt();
                if (version != 1) throw new IllegalStateException("Unsupported skin binary version: " + version);

                int boneCount = in.readInt();
                bones = new String[boneCount];
                for (int i = 0; i < boneCount; i++) {
                    int len = in.readUnsignedShort();
                    bones[i] = new String(in.readNBytes(len), StandardCharsets.UTF_8);
                }
                int nv = in.readInt();
                int nu = in.readInt();
                int nq = in.readInt();

                verts = new float[nv * 3];
                for (int i = 0; i < verts.length; i++) verts[i] = in.readFloat();
                norms = new float[nv * 3];
                for (int i = 0; i < norms.length; i++) norms[i] = in.readFloat();
                uvs = new float[nu * 2];
                for (int i = 0; i < uvs.length; i++) uvs[i] = in.readFloat();
                quads = new int[nq * 4];
                for (int i = 0; i < quads.length; i++) quads[i] = in.readInt();
                quadUvs = new int[nq * 4];
                for (int i = 0; i < quadUvs.length; i++) quadUvs[i] = in.readInt();
                bIdx = new int[nv * 4];
                for (int i = 0; i < bIdx.length; i++) bIdx[i] = in.readUnsignedByte();
                bW = new float[nv * 4];
                for (int i = 0; i < bW.length; i++) bW[i] = in.readFloat();
            } else {
                try (var reader = new InputStreamReader(res, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray bonesArr = root.getAsJsonArray("bones");
                    bones = new String[bonesArr.size()];
                    for (int i = 0; i < bones.length; i++) bones[i] = bonesArr.get(i).getAsString();

                    verts = toFloatArray(root.getAsJsonArray("vertices"));
                    norms = toFloatArray(root.getAsJsonArray("normals"));
                    uvs = toFloatArray(root.getAsJsonArray("uvs"));
                    quads = toIntArray(root.getAsJsonArray("quads"));
                    quadUvs = toIntArray(root.getAsJsonArray("quad_uvs"));
                    bIdx = toIntArray(root.getAsJsonArray("bone_indices"));
                    bW = toFloatArray(root.getAsJsonArray("bone_weights"));
                }
            }

            // Convert Bedrock JSON units (16 = 1 block, X mirrored) into Minecraft model units
            int nV = verts.length / 3;
            for (int i = 0; i < nV; i++) {
                verts[i * 3] = -verts[i * 3] * BLOCK_SCALE;
                verts[i * 3 + 1] = verts[i * 3 + 1] * BLOCK_SCALE;
                verts[i * 3 + 2] = verts[i * 3 + 2] * BLOCK_SCALE;
                norms[i * 3] = -norms[i * 3];
            }

            // Compact to only bones actually referenced by vertices, and sort weights descending if needed
            boolean[] used = new boolean[bones.length];
            boolean hasMultiBone = false;
            for (int i = 0; i < nV; i++) {
                int bi = i * 4;
                if (bW[bi] < bW[bi + 1] || bW[bi + 1] < bW[bi + 2] || bW[bi + 2] < bW[bi + 3]) {
                    sort4Desc(bIdx, bW, bi);
                }
                if (bW[bi + 1] > 0.0001F) hasMultiBone = true;
                for (int k = 0; k < 4; k++) {
                    if (bW[bi + k] > 0.0F) {
                        used[bIdx[bi + k]] = true;
                    }
                }
            }
            int[] remap = new int[bones.length];
            Arrays.fill(remap, -1);
            List<String> activeList = new ArrayList<>();
            for (int b = 0; b < bones.length; b++) {
                if (used[b]) {
                    remap[b] = activeList.size();
                    activeList.add(bones[b]);
                }
            }
            if (activeList.isEmpty() && bones.length > 0) {
                remap[0] = 0;
                activeList.add(bones[0]);
            }
            for (int i = 0; i < bIdx.length; i++) {
                int r = remap[bIdx[i]];
                bIdx[i] = r >= 0 ? r : 0;
            }
            String[] activeBones = activeList.toArray(String[]::new);

            skin = new SkinData(activeBones, verts, norms, uvs, quads, quadUvs, bIdx, bW, hasMultiBone);
            deformedPos = new float[verts.length];
            deformedNorm = new float[norms.length];
            dualQuats = new float[activeBones.length][8];
            boneMats = new float[activeBones.length][12];
            lastBoneMats = new float[activeBones.length * 12];
            if (this.morphBinLoc != null) {
                this.morphTargets = loadMorphs(this.morphBinLoc, nV);
                this.morphedVerts = new float[verts.length];
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load DQS skin mesh: " + skinJsonLoc, e);
        }
    }

    private static Map<String, MorphTarget> loadMorphs(ResourceLocation loc, int maxVerts) {
        Map<String, MorphTarget> map = new HashMap<>();
        try (var res = Minecraft.getInstance().getResourceManager().open(loc)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(res, 65536));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'P' || magic[1] != 'M' || magic[2] != 'R' || magic[3] != 'P') {
                throw new IllegalStateException("Invalid morph binary header: " + loc);
            }
            int version = in.readInt();
            if (version != 1) throw new IllegalStateException("Unsupported morph version: " + version);
            int count = in.readInt();
            for (int m = 0; m < count; m++) {
                int nameLen = in.readUnsignedShort();
                String name = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                int nIdx = in.readInt();
                int[] idx = new int[nIdx];
                for (int i = 0; i < nIdx; i++) {
                    int vi = in.readInt();
                    idx[i] = (vi >= 0 && vi < maxVerts) ? vi : -1;
                }
                float[] d = new float[nIdx * 3];
                for (int i = 0; i < nIdx; i++) {
                    d[i * 3]     = -in.readFloat() * BLOCK_SCALE;
                    d[i * 3 + 1] =  in.readFloat() * BLOCK_SCALE;
                    d[i * 3 + 2] =  in.readFloat() * BLOCK_SCALE;
                }
                map.put(name, new MorphTarget(idx, d));
            }
            LOGGER.info("[Puff Companion] Loaded {} morph targets from {}", map.size(), loc);
        } catch (Exception e) {
            LOGGER.warn("[Puff Companion] Could not load morph targets {}: {}", loc, e.getMessage());
        }
        return map;
    }

    private record MorphTarget(int[] idx, float[] deltas) {}

    private static void sort4Desc(int[] idx, float[] w, int bi) {
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 4; b++) {
                if (w[bi + b] > w[bi + a]) {
                    float tw = w[bi + a]; w[bi + a] = w[bi + b]; w[bi + b] = tw;
                    int ti = idx[bi + a]; idx[bi + a] = idx[bi + b]; idx[bi + b] = ti;
                }
            }
        }
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                          RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                          float partialTick, int packedLight, int packedOverlay) {
        if (!this.visibleWhen.test(animatable)) return;
        ensureLoaded();
        if (!orientationChecked) {
            orientationChecked = true;
            fixNormalOrientation(bakedModel);
        }
        GeoBone[] bones = resolveBones(bakedModel);
        // Ask GeckoLib to record modelSpaceMatrix only for the active bones this mesh uses
        for (GeoBone bone : bones) {
            if (bone != null) bone.setTrackingMatrices(true);
        }
    }

    private GeoBone[] resolveBones(BakedGeoModel bakedModel) {
        if (this.cachedBoneModel == bakedModel && this.cachedBones != null) {
            return this.cachedBones;
        }
        int n = skin.activeBones.length;
        GeoBone[] bones = new GeoBone[n];
        for (int b = 0; b < n; b++) {
            bones[b] = bakedModel.getBone(skin.activeBones[b]).orElse(null);
        }
        this.cachedBoneModel = bakedModel;
        this.cachedBones = bones;
        return bones;
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        if (!this.visibleWhen.test(animatable)) return;
        ensureLoaded();

        int entityId = (animatable instanceof net.minecraft.world.entity.Entity ent) ? ent.getId() : 0;
        boolean sameEntity = hasCachedSkin && entityId == lastEntityId;
        String activeMorph = "";
        if (this.morphProvider != null && this.morphTargets != null) {
            String req = this.morphProvider.apply(animatable);
            if (req != null && this.morphTargets.containsKey(req)) {
                activeMorph = req;
            }
        }
        boolean morphChanged = !Objects.equals(activeMorph, this.lastMorphName);

        // Adaptive time-based skinning throttle: Minecraft ticks at 20 TPS, so 30 Hz close-up (20/10 Hz at distance)
        // stays smooth while eliminating redundant CPU skinning at high refresh rates.
        long nowNanos = System.nanoTime();
        boolean throttleSkip = false;
        if (sameEntity && !morphChanged && !PuffRenderer.guiPreview && animatable instanceof net.minecraft.world.entity.Entity ent) {
            long minIntervalNs = 33_000_000L; // ~30 Hz within 12 blocks
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            if (cam.isInitialized()) {
                double distSq = ent.distanceToSqr(cam.getPosition());
                if (distSq > 576.0) {
                    minIntervalNs = 100_000_000L; // ~10 Hz beyond 24 blocks
                } else if (distSq > 144.0) {
                    minIntervalNs = 50_000_000L; // ~20 Hz for 12-24 blocks
                }
            }
            if ((nowNanos - lastSkinnedNanos) < minIntervalNs) {
                throttleSkip = true;
            }
        }

        if (!throttleSkip) {
            GeoBone[] bones = resolveBones(bakedModel);
            int nBones = bones.length;
            boolean poseChanged = !sameEntity || morphChanged;
            for (int b = 0; b < nBones; b++) {
                GeoBone bone = bones[b];
                float[] m = boneMats[b];
                if (bone == null) {
                    m[0] = 1; m[1] = 0; m[2] = 0; m[3] = 0;
                    m[4] = 0; m[5] = 1; m[6] = 0; m[7] = 0;
                    m[8] = 0; m[9] = 0; m[10] = 1; m[11] = 0;
                    if (skin.hasMultiBone) setIdentityDQ(dualQuats[b]);
                } else {
                    restInv.translation(-bone.getPivotX() * BLOCK_SCALE, -bone.getPivotY() * BLOCK_SCALE, -bone.getPivotZ() * BLOCK_SCALE);
                    delta.set(bone.getModelSpaceMatrix()).mul(restInv);
                    m[0] = delta.m00(); m[1] = delta.m10(); m[2] = delta.m20(); m[3] = delta.m30();
                    m[4] = delta.m01(); m[5] = delta.m11(); m[6] = delta.m21(); m[7] = delta.m31();
                    m[8] = delta.m02(); m[9] = delta.m12(); m[10] = delta.m22(); m[11] = delta.m32();
                }
                if (!poseChanged) {
                    int base = b * 12;
                    for (int k = 0; k < 12; k++) {
                        if (Math.abs(m[k] - lastBoneMats[base + k]) > 5.0E-4F) {
                            poseChanged = true;
                            break;
                        }
                    }
                }
            }

            if (poseChanged) {
                if (skin.hasMultiBone) {
                    for (int b = 0; b < nBones; b++) {
                        float[] m = boneMats[b];
                        delta.set(
                                m[0], m[4], m[8], 0.0F,
                                m[1], m[5], m[9], 0.0F,
                                m[2], m[6], m[10], 0.0F,
                                m[3], m[7], m[11], 1.0F
                        );
                        delta.getUnnormalizedRotation(q).normalize();
                        float tx = m[3], ty = m[7], tz = m[11];
                        float qw = q.w, qx = q.x, qy = q.y, qz = q.z;
                        float[] dq = dualQuats[b];
                        dq[0] = qw; dq[1] = qx; dq[2] = qy; dq[3] = qz;
                        dq[4] = -0.5F * (tx * qx + ty * qy + tz * qz);
                        dq[5] =  0.5F * (tx * qw + ty * qz - tz * qy);
                        dq[6] =  0.5F * (ty * qw + tz * qx - tx * qz);
                        dq[7] =  0.5F * (tz * qw + tx * qy - ty * qx);
                    }
                }
                float[] srcVerts = skin.verts;
                if (!activeMorph.isEmpty() && this.morphedVerts != null) {
                    MorphTarget mt = this.morphTargets.get(activeMorph);
                    if (mt != null) {
                        System.arraycopy(skin.verts, 0, this.morphedVerts, 0, skin.verts.length);
                        int[] idx = mt.idx;
                        float[] d = mt.deltas;
                        for (int i = 0; i < idx.length; i++) {
                            int v = idx[i];
                            if (v < 0) continue;
                            int vi = v * 3;
                            int di = i * 3;
                            this.morphedVerts[vi]     += d[di];
                            this.morphedVerts[vi + 1] += d[di + 1];
                            this.morphedVerts[vi + 2] += d[di + 2];
                        }
                        srcVerts = this.morphedVerts;
                    }
                }
                skinVertices(srcVerts);
                for (int b = 0; b < nBones; b++) {
                    System.arraycopy(boneMats[b], 0, lastBoneMats, b * 12, 12);
                }
                lastEntityId = entityId;
                lastMorphName = activeMorph;
                lastSkinnedNanos = nowNanos;
                hasCachedSkin = true;
                vboDirty = true;
            } else {
                lastSkinnedNanos = nowNanos;
            }
        }

        // 3. Emit deformed quads directly into Minecraft's VertexConsumer
        Matrix4f pose;
        Matrix3f normMat;
        if (getRenderer() instanceof ModelPoseProvider provider && provider.getModelRenderPose() != null) {
            pose = provider.getModelRenderPose();
            normMat = provider.getModelRenderNormal();
        } else {
            pose = poseStack.last().pose();
            normMat = poseStack.last().normal();
        }
        if (!normMatChecked) {
            normMatChecked = true;
            LOGGER.info("[Puff Companion] {}: normal matrix determinant = {} ({})", textureLoc,
                    normMat.determinant(), normMat.determinant() > 0 ? "rotation only, OK" : "MIRRORED");
        }
        // No culling: hair strands / bikini are single-sided thin sheets
        ResourceLocation activeTex = this.textureProvider != null ? this.textureProvider.apply(animatable) : textureLoc;
        if (activeTex == null) activeTex = textureLoc;

        if (!PuffRenderer.guiPreview && bufferSource instanceof MultiBufferSource.BufferSource && !isShaderPackInUse()) {
            renderViaVbo(activeTex, pose, packedLight, packedOverlay);
            return;
        }

        renderViaVertexConsumer(activeTex, bufferSource, pose, normMat, packedLight, packedOverlay);
    }

    private void renderViaVbo(ResourceLocation activeTex, Matrix4f pose, int packedLight, int packedOverlay) {
        if (cachedVbo == null || vboDirty || packedLight != lastVboLight || packedOverlay != lastVboOverlay) {
            final int[] quads = skin.quads;
            final int[] quadUvs = skin.quadUvs;
            final float[] uvs = skin.uvs;
            final int nQ = quads.length / 4;
            if (vboBuilder == null) {
                vboBuilder = new BufferBuilder(Math.max(4096, nQ * 4 * DefaultVertexFormat.NEW_ENTITY.getVertexSize()));
            }
            vboBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            if (pureUpNormals) {
                for (int qi = 0; qi < nQ; qi++) {
                    int base = qi * 4;
                    for (int c = 3; c >= 0; c--) {
                        int vi = quads[base + c] * 3;
                        int ui = quadUvs[base + c] * 2;
                        vboBuilder.vertex(
                                deformedPos[vi], deformedPos[vi + 1], deformedPos[vi + 2],
                                1.0F, 1.0F, 1.0F, 1.0F,
                                uvs[ui], uvs[ui + 1],
                                packedOverlay, packedLight,
                                0.0F, 1.0F, 0.0F
                        );
                    }
                }
            } else {
                for (int qi = 0; qi < nQ; qi++) {
                    int base = qi * 4;
                    for (int c = 3; c >= 0; c--) {
                        int vi = quads[base + c] * 3;
                        int ui = quadUvs[base + c] * 2;
                        vboBuilder.vertex(
                                deformedPos[vi], deformedPos[vi + 1], deformedPos[vi + 2],
                                1.0F, 1.0F, 1.0F, 1.0F,
                                uvs[ui], uvs[ui + 1],
                                packedOverlay, packedLight,
                                deformedNorm[vi], deformedNorm[vi + 1], deformedNorm[vi + 2]
                        );
                    }
                }
            }
            BufferBuilder.RenderedBuffer rendered = vboBuilder.end();
            if (cachedVbo == null) {
                cachedVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            cachedVbo.bind();
            cachedVbo.upload(rendered);
            VertexBuffer.unbind();
            vboDirty = false;
            lastVboLight = packedLight;
            lastVboOverlay = packedOverlay;
        }

        RenderType rt = RenderType.entityCutoutNoCull(activeTex);
        rt.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            vboModelView.set(RenderSystem.getModelViewMatrix()).mul(pose);
            cachedVbo.bind();
            cachedVbo.drawWithShader(vboModelView, RenderSystem.getProjectionMatrix(), shader);
            VertexBuffer.unbind();
        }
        rt.clearRenderState();
    }

    private void renderViaVertexConsumer(ResourceLocation activeTex, MultiBufferSource bufferSource,
                                         Matrix4f pose, Matrix3f normMat, int packedLight, int packedOverlay) {
        final float p00 = pose.m00(), p10 = pose.m10(), p20 = pose.m20(), p30 = pose.m30();
        final float p01 = pose.m01(), p11 = pose.m11(), p21 = pose.m21(), p31 = pose.m31();
        final float p02 = pose.m02(), p12 = pose.m12(), p22 = pose.m22(), p32 = pose.m32();
        final float n00 = normMat.m00(), n10 = normMat.m10(), n20 = normMat.m20();
        final float n01 = normMat.m01(), n11 = normMat.m11(), n21 = normMat.m21();
        final float n02 = normMat.m02(), n12 = normMat.m12(), n22 = normMat.m22();

        final int nV = deformedPos.length / 3;
        if (transformedPos == null || transformedPos.length != deformedPos.length) {
            transformedPos = new float[deformedPos.length];
        }
        for (int i = 0; i < nV; i++) {
            int vi = i * 3;
            float x = deformedPos[vi], y = deformedPos[vi + 1], z = deformedPos[vi + 2];
            transformedPos[vi]     = p00 * x + p10 * y + p20 * z + p30;
            transformedPos[vi + 1] = p01 * x + p11 * y + p21 * z + p31;
            transformedPos[vi + 2] = p02 * x + p12 * y + p22 * z + p32;
        }

        VertexConsumer out = bufferSource.getBuffer(RenderType.entityCutoutNoCull(activeTex));
        final int[] quads = skin.quads;
        final int[] quadUvs = skin.quadUvs;
        final float[] uvs = skin.uvs;
        final int nQ = quads.length / 4;
        if (pureUpNormals) {
            for (int qi = 0; qi < nQ; qi++) {
                int base = qi * 4;
                for (int c = 3; c >= 0; c--) {
                    int vi = quads[base + c] * 3;
                    int ui = quadUvs[base + c] * 2;
                    out.vertex(
                            transformedPos[vi], transformedPos[vi + 1], transformedPos[vi + 2],
                            1.0F, 1.0F, 1.0F, 1.0F,
                            uvs[ui], uvs[ui + 1],
                            packedOverlay, packedLight,
                            n10, n11, n12
                    );
                }
            }
        } else {
            if (transformedNorm == null || transformedNorm.length != deformedNorm.length) {
                transformedNorm = new float[deformedNorm.length];
            }
            for (int i = 0; i < nV; i++) {
                int vi = i * 3;
                float nx = deformedNorm[vi], ny = deformedNorm[vi + 1], nz = deformedNorm[vi + 2];
                transformedNorm[vi]     = n00 * nx + n10 * ny + n20 * nz;
                transformedNorm[vi + 1] = n01 * nx + n11 * ny + n21 * nz;
                transformedNorm[vi + 2] = n02 * nx + n12 * ny + n22 * nz;
            }
            for (int qi = 0; qi < nQ; qi++) {
                int base = qi * 4;
                for (int c = 3; c >= 0; c--) {
                    int vi = quads[base + c] * 3;
                    int ui = quadUvs[base + c] * 2;
                    out.vertex(
                            transformedPos[vi], transformedPos[vi + 1], transformedPos[vi + 2],
                            1.0F, 1.0F, 1.0F, 1.0F,
                            uvs[ui], uvs[ui + 1],
                            packedOverlay, packedLight,
                            transformedNorm[vi], transformedNorm[vi + 1], transformedNorm[vi + 2]
                    );
                }
            }
        }
    }

    private void skinVertices(float[] verts) {
        final float[] norms = skin.norms;
        final int[] boneIdx = skin.boneIdx;
        final float[] boneW = skin.boneW;
        final float keep = 1.0F - normalUpBlend;
        final float upBlend = normalUpBlend;
        final boolean skipNormals = pureUpNormals;
        final int nV = verts.length / 3;

        for (int i = 0; i < nV; i++) {
            int bi = i * 4;
            int vi = i * 3;
            float px = verts[vi], py = verts[vi + 1], pz = verts[vi + 2];
            float w1 = boneW[bi + 1];

            if (w1 <= 0.0001F) {
                // Fast path 1: rigid single-bone vertex (no quaternion math, no DQS sqrt)
                float[] m = boneMats[boneIdx[bi]];
                deformedPos[vi]     = m[0] * px + m[1] * py + m[2]  * pz + m[3];
                deformedPos[vi + 1] = m[4] * px + m[5] * py + m[6]  * pz + m[7];
                deformedPos[vi + 2] = m[8] * px + m[9] * py + m[10] * pz + m[11];

                if (!skipNormals) {
                    float nx = norms[vi], ny = norms[vi + 1], nz = norms[vi + 2];
                    float snx = (m[0] * nx + m[1] * ny + m[2]  * nz) * keep;
                    float sny = (m[4] * nx + m[5] * ny + m[6]  * nz) * keep + upBlend;
                    float snz = (m[8] * nx + m[9] * ny + m[10] * nz) * keep;
                    float nLen = (float) (1.0 / Math.max(1e-6, Math.sqrt(snx * snx + sny * sny + snz * snz)));
                    deformedNorm[vi]     = snx * nLen;
                    deformedNorm[vi + 1] = sny * nLen;
                    deformedNorm[vi + 2] = snz * nLen;
                }
                continue;
            }

            float[] dq0 = dualQuats[boneIdx[bi]];
            float w0 = boneW[bi];
            float rw = dq0[0] * w0, rx = dq0[1] * w0, ry = dq0[2] * w0, rz = dq0[3] * w0;
            float dw = dq0[4] * w0, dx = dq0[5] * w0, dy = dq0[6] * w0, dz = dq0[7] * w0;

            float[] dq1 = dualQuats[boneIdx[bi + 1]];
            float dot1 = dq0[0] * dq1[0] + dq0[1] * dq1[1] + dq0[2] * dq1[2] + dq0[3] * dq1[3];
            float s1 = dot1 < 0.0F ? -w1 : w1;
            rw += dq1[0] * s1; rx += dq1[1] * s1; ry += dq1[2] * s1; rz += dq1[3] * s1;
            dw += dq1[4] * s1; dx += dq1[5] * s1; dy += dq1[6] * s1; dz += dq1[7] * s1;

            if (boneW[bi + 2] > 0.0001F) {
                for (int k = 2; k < 4; k++) {
                    float wk = boneW[bi + k];
                    if (wk <= 0.0001F) break;
                    float[] dqk = dualQuats[boneIdx[bi + k]];
                    float dot = dq0[0] * dqk[0] + dq0[1] * dqk[1] + dq0[2] * dqk[2] + dq0[3] * dqk[3];
                    float s = dot < 0.0F ? -wk : wk;
                    rw += dqk[0] * s; rx += dqk[1] * s; ry += dqk[2] * s; rz += dqk[3] * s;
                    dw += dqk[4] * s; dx += dqk[5] * s; dy += dqk[6] * s; dz += dqk[7] * s;
                }
            }

            float invLen = (float) (1.0 / Math.max(1e-8, Math.sqrt(rw * rw + rx * rx + ry * ry + rz * rz)));
            rw *= invLen; rx *= invLen; ry *= invLen; rz *= invLen;
            dw *= invLen; dx *= invLen; dy *= invLen; dz *= invLen;

            float tx = 2.0F * (-dw * rx + dx * rw - dy * rz + dz * ry);
            float ty = 2.0F * (-dw * ry + dx * rz + dy * rw - dz * rx);
            float tz = 2.0F * (-dw * rz - dx * ry + dy * rx + dz * rw);

            float cx = 2.0F * (ry * pz - rz * py), cy = 2.0F * (rz * px - rx * pz), cz = 2.0F * (rx * py - ry * px);
            deformedPos[vi]     = px + rw * cx + (ry * cz - rz * cy) + tx;
            deformedPos[vi + 1] = py + rw * cy + (rz * cx - rx * cz) + ty;
            deformedPos[vi + 2] = pz + rw * cz + (rx * cy - ry * cx) + tz;

            if (!skipNormals) {
                float nx = norms[vi], ny = norms[vi + 1], nz = norms[vi + 2];
                float ncx = 2.0F * (ry * nz - rz * ny), ncy = 2.0F * (rz * nx - rx * nz), ncz = 2.0F * (rx * ny - ry * nx);
                float snx = (nx + rw * ncx + (ry * ncz - rz * ncy)) * keep;
                float sny = (ny + rw * ncy + (rz * ncx - rx * ncz)) * keep + upBlend;
                float snz = (nz + rw * ncz + (rx * ncy - ry * ncx)) * keep;
                float nLen = (float) (1.0 / Math.max(1e-6, Math.sqrt(snx * snx + sny * sny + snz * snz)));
                deformedNorm[vi]     = snx * nLen;
                deformedNorm[vi + 1] = sny * nLen;
                deformedNorm[vi + 2] = snz * nLen;
            }
        }
    }

    private static void setIdentityDQ(float[] dq) {
        dq[0] = 1.0F; dq[1] = 0; dq[2] = 0; dq[3] = 0;
        dq[4] = 0; dq[5] = 0; dq[6] = 0; dq[7] = 0;
    }

    private static float[] toFloatArray(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsFloat();
        return out;
    }

    private static int[] toIntArray(JsonArray arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsInt();
        return out;
    }

    private record SkinData(
        String[] activeBones, float[] verts, float[] norms, float[] uvs,
        int[] quads, int[] quadUvs, int[] boneIdx, float[] boneW, boolean hasMultiBone
    ) {}
}
