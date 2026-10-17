package com.realistic_bullets.client.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.realistic_bullets.init.ModBlocks;
import com.realistic_bullets.voxel.DamageModelData;
import com.realistic_bullets.voxel.VoxelVolume;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.IForgeBakedModel;
import net.minecraftforge.client.model.IQuadTransformer;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.pipeline.QuadBakingVertexConsumer;

public final class DamageQuadModelCache {
    private static final int MAX_MESH_CACHE_SIZE = 4096;
    private static final float UV_EPSILON = 1.0E-6F;
    private static final Map<ModelCacheKey, CompiledModel> MODEL_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ModelCacheKey, CompiledModel> eldest) {
                    return size() > MAX_MESH_CACHE_SIZE;
                }
            });

    private DamageQuadModelCache() {
    }

    public static void clearCaches() {
        synchronized (MODEL_CACHE) {
            MODEL_CACHE.clear();
        }
    }

    public static CompiledModel getOrBuildModel(DamageModelData modelData) {
        ModelCacheKey key = new ModelCacheKey(modelData);
        synchronized (MODEL_CACHE) {
            return MODEL_CACHE.computeIfAbsent(key, DamageQuadModelCache::buildModel);
        }
    }

    private static CompiledModel buildModel(ModelCacheKey cacheKey) {
        DamageModelData modelData = cacheKey.modelData();
        BlockState originalState = modelData.originalState();
        if (originalState.isAir() || originalState.getBlock() == ModBlocks.DAMAGED_BLOCK.get()) {
            return CompiledModel.empty();
        }

        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        BakedModel originalModel = blockRenderer.getBlockModel(originalState);
        IForgeBakedModel forgeModel = (IForgeBakedModel) originalModel;
        long modelSeed = modelData.modelSeed();
        RenderType renderType = pickRenderType(originalModel, originalState, modelSeed);
        boolean ambientOcclusion = forgeModel.useAmbientOcclusion(originalState, renderType);
        FaceTextures faceTextures = createFaceTextures(forgeModel, originalModel, originalState, modelSeed, renderType, ambientOcclusion);
        long[] voxels = modelData.voxels();

        List<BakedQuad>[] culledQuads = createQuadBuckets();
        List<BakedQuad> unculledQuads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            appendDirectionQuads(voxels, direction, faceTextures, modelData, culledQuads[direction.ordinal()], unculledQuads);
        }

        return unculledQuads.isEmpty() && areAllEmpty(culledQuads)
                ? CompiledModel.empty()
                : new CompiledModel(freezeQuadBuckets(culledQuads), List.copyOf(unculledQuads), renderType);
    }

    private static RenderType pickRenderType(BakedModel model, BlockState state, long modelSeed) {
        for (RenderType renderType : model.getRenderTypes(state, RandomSource.create(modelSeed), ModelData.EMPTY)) {
            return renderType;
        }
        return RenderType.solid();
    }

    private static FaceTextures createFaceTextures(IForgeBakedModel model, BakedModel originalModel, BlockState state, long modelSeed, RenderType renderType, boolean ambientOcclusion) {
        TextureAtlasSprite particle = originalModel.getParticleIcon(ModelData.EMPTY);
        FaceMaterial[] faces = new FaceMaterial[Direction.values().length];

        for (Direction direction : Direction.values()) {
            faces[direction.ordinal()] = pickFaceMaterial(model, state, direction, particle, modelSeed, renderType, ambientOcclusion);
        }

        return new FaceTextures(faces);
    }

    private static FaceMaterial pickFaceMaterial(IForgeBakedModel model, BlockState state, Direction direction, TextureAtlasSprite fallback, long modelSeed, RenderType renderType, boolean ambientOcclusion) {
        BakedQuad quad = firstFaceQuad(model, state, direction, modelSeed, renderType);
        if (quad == null) {
            return new FaceMaterial(fallback, -1, new FallbackUvMapper(direction), true, ambientOcclusion);
        }

        return new FaceMaterial(
                quad.getSprite(),
                quad.isTinted() ? quad.getTintIndex() : -1,
                createUvMapper(direction, quad),
                quad.isShade(),
                ambientOcclusion
        );
    }

    @Nullable
    private static BakedQuad firstFaceQuad(IForgeBakedModel model, BlockState state, Direction direction, long modelSeed, RenderType renderType) {
        List<BakedQuad> quads = model.getQuads(state, direction, RandomSource.create(modelSeed), ModelData.EMPTY, renderType);
        if (!quads.isEmpty()) {
            return quads.get(0);
        }

        for (BakedQuad quad : model.getQuads(state, null, RandomSource.create(modelSeed), ModelData.EMPTY, renderType)) {
            if (quad.getDirection() == direction) {
                return quad;
            }
        }
        return null;
    }

    private static UvMapper createUvMapper(Direction direction, BakedQuad quad) {
        PlanePoint[] points = extractPlanePoints(direction, quad);
        for (int baseIndex = 0; baseIndex < points.length; baseIndex++) {
            for (int firstIndex = 0; firstIndex < points.length; firstIndex++) {
                if (firstIndex == baseIndex) {
                    continue;
                }
                for (int secondIndex = firstIndex + 1; secondIndex < points.length; secondIndex++) {
                    if (secondIndex == baseIndex) {
                        continue;
                    }
                    UvMapper mapper = tryCreateAffineMapper(direction, points[baseIndex], points[firstIndex], points[secondIndex]);
                    if (mapper != null) {
                        return mapper;
                    }
                }
            }
        }
        return new FallbackUvMapper(direction);
    }

    private static PlanePoint[] extractPlanePoints(Direction direction, BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        PlanePoint[] points = new PlanePoint[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride;
            float x = Float.intBitsToFloat(vertices[offset + IQuadTransformer.POSITION]);
            float y = Float.intBitsToFloat(vertices[offset + IQuadTransformer.POSITION + 1]);
            float z = Float.intBitsToFloat(vertices[offset + IQuadTransformer.POSITION + 2]);
            float u = Float.intBitsToFloat(vertices[offset + IQuadTransformer.UV0]);
            float v = Float.intBitsToFloat(vertices[offset + IQuadTransformer.UV0 + 1]);
            float[] plane = project(direction, x, y, z);
            points[vertex] = new PlanePoint(plane[0], plane[1], u, v);
        }
        return points;
    }

    @Nullable
    private static UvMapper tryCreateAffineMapper(Direction direction, PlanePoint origin, PlanePoint first, PlanePoint second) {
        float da1 = first.a() - origin.a();
        float db1 = first.b() - origin.b();
        float da2 = second.a() - origin.a();
        float db2 = second.b() - origin.b();
        float determinant = da1 * db2 - db1 * da2;
        if (Math.abs(determinant) < UV_EPSILON) {
            return null;
        }

        float du1 = first.u() - origin.u();
        float du2 = second.u() - origin.u();
        float dv1 = first.v() - origin.v();
        float dv2 = second.v() - origin.v();

        float invDeterminant = 1.0F / determinant;
        float uA = (du1 * db2 - db1 * du2) * invDeterminant;
        float uB = (da1 * du2 - du1 * da2) * invDeterminant;
        float vA = (dv1 * db2 - db1 * dv2) * invDeterminant;
        float vB = (da1 * dv2 - dv1 * da2) * invDeterminant;
        float baseU = origin.u() - uA * origin.a() - uB * origin.b();
        float baseV = origin.v() - vA * origin.a() - vB * origin.b();

        return new AffineUvMapper(direction, baseU, uA, uB, baseV, vA, vB);
    }

    private static void appendDirectionQuads(long[] voxels, Direction direction, FaceTextures faceTextures, DamageModelData modelData, List<BakedQuad> culledQuads, List<BakedQuad> unculledQuads) {
        for (int slice = 0; slice < VoxelVolume.SIZE; slice++) {
            FaceMaterial material = faceTextures.forFace(direction);
            boolean boundaryFace = isOuterSlice(direction, slice);
            boolean[][] visited = new boolean[VoxelVolume.SIZE][VoxelVolume.SIZE];

            for (int v = 0; v < VoxelVolume.SIZE; v++) {
                for (int u = 0; u < VoxelVolume.SIZE; u++) {
                    if (visited[v][u] || !hasExposedFace(voxels, direction, slice, u, v)) {
                        continue;
                    }

                    int width = 1;
                    while (u + width < VoxelVolume.SIZE && !visited[v][u + width] && hasExposedFace(voxels, direction, slice, u + width, v)) {
                        width++;
                    }

                    int height = 1;
                    heightLoop:
                    while (v + height < VoxelVolume.SIZE) {
                        for (int scan = 0; scan < width; scan++) {
                            if (visited[v + height][u + scan] || !hasExposedFace(voxels, direction, slice, u + scan, v + height)) {
                                break heightLoop;
                            }
                        }
                        height++;
                    }

                    for (int markV = v; markV < v + height; markV++) {
                        for (int markU = u; markU < u + width; markU++) {
                            visited[markV][markU] = true;
                        }
                    }

                    BakedQuad quad = buildQuad(direction, slice, u, v, width, height, material, modelData);
                    if (boundaryFace) {
                        culledQuads.add(quad);
                    } else {
                        unculledQuads.add(quad);
                    }
                }
            }
        }
    }

    private static boolean hasExposedFace(long[] voxels, Direction direction, int slice, int u, int v) {
        int x;
        int y;
        int z;

        switch (direction) {
            case DOWN, UP -> {
                x = u;
                y = slice;
                z = v;
            }
            case NORTH, SOUTH -> {
                x = u;
                y = v;
                z = slice;
            }
            case WEST, EAST -> {
                x = slice;
                y = v;
                z = u;
            }
            default -> throw new IllegalStateException("Unexpected direction: " + direction);
        }

        return VoxelVolume.isFilled(voxels, x, y, z)
                && !VoxelVolume.isFilled(voxels, x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
    }

    private static boolean isOuterSlice(Direction direction, int slice) {
        return switch (direction) {
            case DOWN, NORTH, WEST -> slice == 0;
            case UP, SOUTH, EAST -> slice == VoxelVolume.SIZE - 1;
        };
    }

    private static BakedQuad buildQuad(Direction direction, int slice, int u, int v, int width, int height, FaceMaterial material, DamageModelData modelData) {
        Bounds bounds = Bounds.forFace(direction, slice, u, v, width, height);
        QuadBakingVertexConsumer.Buffered buffer = new QuadBakingVertexConsumer.Buffered();
        buffer.setDirection(direction);
        buffer.setTintIndex(material.tintIndex());
        buffer.setSprite(material.sprite());
        buffer.setShade(material.shade());
        buffer.setHasAmbientOcclusion(material.ambientOcclusion());

        emitFace(buffer, direction, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), material,
                (float) modelData.offsetX(), (float) modelData.offsetY(), (float) modelData.offsetZ());
        return buffer.getQuad();
    }

    private static void emitFace(VertexConsumer buffer, Direction direction,
                                 float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                 FaceMaterial material, float offsetX, float offsetY, float offsetZ) {
        switch (direction) {
            case DOWN -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    minX, minY, minZ,
                    maxX, minY, minZ,
                    maxX, minY, maxZ,
                    minX, minY, maxZ);
            case UP -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    minX, maxY, maxZ,
                    maxX, maxY, maxZ,
                    maxX, maxY, minZ,
                    minX, maxY, minZ);
            case NORTH -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    maxX, minY, minZ,
                    minX, minY, minZ,
                    minX, maxY, minZ,
                    maxX, maxY, minZ);
            case SOUTH -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    minX, minY, maxZ,
                    maxX, minY, maxZ,
                    maxX, maxY, maxZ,
                    minX, maxY, maxZ);
            case WEST -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    minX, minY, minZ,
                    minX, minY, maxZ,
                    minX, maxY, maxZ,
                    minX, maxY, minZ);
            case EAST -> emitQuad(buffer, direction, material, offsetX, offsetY, offsetZ,
                    maxX, minY, maxZ,
                    maxX, minY, minZ,
                    maxX, maxY, minZ,
                    maxX, maxY, maxZ);
        }
    }

    private static void emitQuad(VertexConsumer buffer, Direction direction, FaceMaterial material, float offsetX, float offsetY, float offsetZ,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3) {
        emitVertex(buffer, direction, material, x0, y0, z0, offsetX, offsetY, offsetZ);
        emitVertex(buffer, direction, material, x1, y1, z1, offsetX, offsetY, offsetZ);
        emitVertex(buffer, direction, material, x2, y2, z2, offsetX, offsetY, offsetZ);
        emitVertex(buffer, direction, material, x3, y3, z3, offsetX, offsetY, offsetZ);
    }

    private static void emitVertex(VertexConsumer buffer, Direction direction, FaceMaterial material, float x, float y, float z, float offsetX, float offsetY, float offsetZ) {
        buffer.vertex(x + offsetX, y + offsetY, z + offsetZ)
                .color(255, 255, 255, 255)
                .uv(material.uvMapper().u(x, y, z, material.sprite()), material.uvMapper().v(x, y, z, material.sprite()))
                .uv2(0, 0)
                .normal(direction.getStepX(), direction.getStepY(), direction.getStepZ())
                .endVertex();
    }

    private static float[] project(Direction direction, float x, float y, float z) {
        return switch (direction) {
            case DOWN, UP -> new float[]{x, z};
            case NORTH, SOUTH -> new float[]{x, y};
            case WEST, EAST -> new float[]{z, y};
        };
    }

    public record CompiledModel(List<BakedQuad>[] culledQuads, List<BakedQuad> unculledQuads, RenderType renderType) {
        private static CompiledModel empty() {
            return new CompiledModel(freezeQuadBuckets(createQuadBuckets()), List.of(), RenderType.solid());
        }

        public List<BakedQuad> quads(@Nullable Direction side) {
            return side == null ? unculledQuads : culledQuads[side.ordinal()];
        }
    }

    private record ModelCacheKey(DamageModelData modelData) {
    }

    private record FaceTextures(FaceMaterial[] faces) {
        private FaceMaterial forFace(Direction direction) {
            return faces[direction.ordinal()];
        }
    }

    private record FaceMaterial(TextureAtlasSprite sprite, int tintIndex, UvMapper uvMapper, boolean shade, boolean ambientOcclusion) {
    }

    private record PlanePoint(float a, float b, float u, float v) {
    }

    private interface UvMapper {
        float u(float x, float y, float z, TextureAtlasSprite sprite);

        float v(float x, float y, float z, TextureAtlasSprite sprite);
    }

    private record AffineUvMapper(Direction direction, float baseU, float uA, float uB, float baseV, float vA, float vB) implements UvMapper {
        @Override
        public float u(float x, float y, float z, TextureAtlasSprite sprite) {
            float[] plane = project(direction, x, y, z);
            return baseU + uA * plane[0] + uB * plane[1];
        }

        @Override
        public float v(float x, float y, float z, TextureAtlasSprite sprite) {
            float[] plane = project(direction, x, y, z);
            return baseV + vA * plane[0] + vB * plane[1];
        }
    }

    private record FallbackUvMapper(Direction direction) implements UvMapper {
        @Override
        public float u(float x, float y, float z, TextureAtlasSprite sprite) {
            return switch (direction) {
                case DOWN, UP, NORTH, SOUTH -> sprite.getU(x * 16.0F);
                case WEST, EAST -> sprite.getU(z * 16.0F);
            };
        }

        @Override
        public float v(float x, float y, float z, TextureAtlasSprite sprite) {
            return switch (direction) {
                case DOWN, UP -> sprite.getV(z * 16.0F);
                case NORTH, SOUTH, WEST, EAST -> sprite.getV((1.0F - y) * 16.0F);
            };
        }
    }

    private record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        private static Bounds forFace(Direction direction, int slice, int u, int v, int width, int height) {
            float sliceMin = slice / 16.0F;
            float sliceMax = (slice + 1) / 16.0F;
            return switch (direction) {
                case DOWN, UP -> new Bounds(
                        u / 16.0F,
                        sliceMin,
                        v / 16.0F,
                        (u + width) / 16.0F,
                        sliceMax,
                        (v + height) / 16.0F
                );
                case NORTH, SOUTH -> new Bounds(
                        u / 16.0F,
                        v / 16.0F,
                        sliceMin,
                        (u + width) / 16.0F,
                        (v + height) / 16.0F,
                        sliceMax
                );
                case WEST, EAST -> new Bounds(
                        sliceMin,
                        v / 16.0F,
                        u / 16.0F,
                        sliceMax,
                        (v + height) / 16.0F,
                        (u + width) / 16.0F
                );
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] createQuadBuckets() {
        List<BakedQuad>[] buckets = (List<BakedQuad>[]) new List<?>[Direction.values().length];
        for (Direction direction : Direction.values()) {
            buckets[direction.ordinal()] = new ArrayList<>();
        }
        return buckets;
    }

    private static List<BakedQuad>[] freezeQuadBuckets(List<BakedQuad>[] buckets) {
        for (Direction direction : Direction.values()) {
            buckets[direction.ordinal()] = List.copyOf(buckets[direction.ordinal()]);
        }
        return buckets;
    }

    private static boolean areAllEmpty(List<BakedQuad>[] buckets) {
        for (Direction direction : Direction.values()) {
            if (!buckets[direction.ordinal()].isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
