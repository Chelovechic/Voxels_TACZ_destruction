package com.realistic_bullets.voxel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class VoxelVolume {
    public static final int SIZE = 16;
    public static final int TOTAL_VOXELS = SIZE * SIZE * SIZE;
    public static final int LONG_COUNT = TOTAL_VOXELS / Long.SIZE;
    private static final int COLLISION_CELL_SIZE = 4;
    private static final int COLLISION_GRID_SIZE = SIZE / COLLISION_CELL_SIZE;
    private static final double SHAPE_EPSILON = 1.0E-6D;
    private static final double HIT_EPSILON = 1.0E-4D;
    private static final int MASK_CACHE_LIMIT = 4096;
    private static final Map<BlockState, long[]> MASK_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockState, long[]> eldest) {
                    return size() > MASK_CACHE_LIMIT;
                }
            });

    private VoxelVolume() {
    }

    public static long[] emptyMask() {
        return new long[LONG_COUNT];
    }

    public static long[] copy(long[] source) {
        long[] copy = new long[LONG_COUNT];
        if (source != null) {
            System.arraycopy(source, 0, copy, 0, Math.min(source.length, LONG_COUNT));
        }
        return copy;
    }

    public static long[] createMask(BlockGetter level, BlockPos pos, BlockState state) {
        long[] cachedMask;
        synchronized (MASK_CACHE) {
            cachedMask = MASK_CACHE.get(state);
        }
        if (cachedMask != null) {
            return copy(cachedMask);
        }

        long[] computedMask = createMaskUncached(level, pos, state);
        synchronized (MASK_CACHE) {
            MASK_CACHE.put(state, copy(computedMask));
        }
        return computedMask;
    }

    private static long[] createMaskUncached(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        long[] mask = emptyMask();
        if (shape.isEmpty()) {
            return mask;
        }

        for (AABB aabb : shape.toAabbs()) {
            fillBox(mask, aabb);
        }
        return mask;
    }

    public static boolean isEmpty(long[] mask) {
        for (long value : mask) {
            if (value != 0L) {
                return false;
            }
        }
        return true;
    }

    public static boolean isFilled(long[] mask, int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return false;
        }
        int index = index(x, y, z);
        return (mask[index >> 6] & (1L << (index & 63))) != 0L;
    }

    public static boolean clear(long[] mask, int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return false;
        }
        int index = index(x, y, z);
        long bit = 1L << (index & 63);
        int bucket = index >> 6;
        if ((mask[bucket] & bit) == 0L) {
            return false;
        }
        mask[bucket] &= ~bit;
        return true;
    }

    public static int countFilled(long[] mask) {
        int count = 0;
        for (long value : mask) {
            count += Long.bitCount(value);
        }
        return count;
    }

    public static VoxelShape buildShape(long[] mask) {
        if (isEmpty(mask)) {
            return Shapes.empty();
        }

        return buildCoarseCollisionShape(mask);
    }

    public static VoxelShape buildTraceShape(long[] mask) {
        if (isEmpty(mask)) {
            return Shapes.empty();
        }

        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(SIZE, SIZE, SIZE);
        for (int bucket = 0; bucket < LONG_COUNT; bucket++) {
            long bits = mask[bucket];
            while (bits != 0L) {
                int bitIndex = Long.numberOfTrailingZeros(bits);
                int voxelIndex = (bucket << 6) | bitIndex;
                int x = voxelIndex & 15;
                int z = (voxelIndex >> 4) & 15;
                int y = voxelIndex >> 8;
                discrete.fill(x, y, z);
                bits &= bits - 1L;
            }
        }
        return CachedMaskVoxelShape.create(discrete);
    }

    public static int[] traceFirstFilledVoxel(long[] mask, BlockPos pos, Vec3 start, Vec3 end) {
        Vec3 localStart = start.subtract(pos.getX(), pos.getY(), pos.getZ());
        Vec3 localEnd = end.subtract(pos.getX(), pos.getY(), pos.getZ());
        Vec3 delta = localEnd.subtract(localStart);
        if (delta.lengthSqr() < HIT_EPSILON * HIT_EPSILON) {
            return null;
        }

        SegmentClip clip = clipToUnitCube(localStart, localEnd);
        if (clip == null) {
            return null;
        }

        double entrySampleT = Math.min(clip.exitT(), clip.entryT() + HIT_EPSILON);
        Vec3 sample = localStart.add(delta.scale(entrySampleT));
        int currentX = toVoxel(sample.x);
        int currentY = toVoxel(sample.y);
        int currentZ = toVoxel(sample.z);
        if (!isInside(currentX, currentY, currentZ)) {
            return null;
        }

        if (isFilled(mask, currentX, currentY, currentZ)) {
            return new int[]{currentX, currentY, currentZ};
        }

        double rayX = clip.localEnd().x - clip.localStart().x;
        double rayY = clip.localEnd().y - clip.localStart().y;
        double rayZ = clip.localEnd().z - clip.localStart().z;

        int stepX = sign(rayX);
        int stepY = sign(rayY);
        int stepZ = sign(rayZ);

        double tMaxX = initialBoundaryT(clip.localStart().x, rayX, currentX, stepX);
        double tMaxY = initialBoundaryT(clip.localStart().y, rayY, currentY, stepY);
        double tMaxZ = initialBoundaryT(clip.localStart().z, rayZ, currentZ, stepZ);
        double tDeltaX = stepDelta(rayX);
        double tDeltaY = stepDelta(rayY);
        double tDeltaZ = stepDelta(rayZ);

        while (true) {
            double nextT;
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    currentX += stepX;
                    nextT = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    currentZ += stepZ;
                    nextT = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                currentY += stepY;
                nextT = tMaxY;
                tMaxY += tDeltaY;
            } else {
                currentZ += stepZ;
                nextT = tMaxZ;
                tMaxZ += tDeltaZ;
            }

            if (nextT > 1.0D + HIT_EPSILON || !isInside(currentX, currentY, currentZ)) {
                return null;
            }

            if (isFilled(mask, currentX, currentY, currentZ)) {
                return new int[]{currentX, currentY, currentZ};
            }
        }
    }

    public static int[] findImpactVoxel(long[] mask, BlockPos pos, Vec3 hitLocation, Direction face) {
        Vec3 sample = hitLocation.subtract(face.getStepX() * HIT_EPSILON, face.getStepY() * HIT_EPSILON, face.getStepZ() * HIT_EPSILON);
        double localX = Mth.clamp(sample.x - pos.getX(), 0.0D, 0.999999D);
        double localY = Mth.clamp(sample.y - pos.getY(), 0.0D, 0.999999D);
        double localZ = Mth.clamp(sample.z - pos.getZ(), 0.0D, 0.999999D);

        int x = Mth.clamp((int) Math.floor(localX * SIZE), 0, SIZE - 1);
        int y = Mth.clamp((int) Math.floor(localY * SIZE), 0, SIZE - 1);
        int z = Mth.clamp((int) Math.floor(localZ * SIZE), 0, SIZE - 1);
        if (isFilled(mask, x, y, z)) {
            return new int[]{x, y, z};
        }

        int[] aligned = findAlongImpactAxis(mask, x, y, z, face);
        if (aligned != null) {
            return aligned;
        }

        double bestDistance = Double.MAX_VALUE;
        int[] best = null;
        for (int scanY = 0; scanY < SIZE; scanY++) {
            for (int scanZ = 0; scanZ < SIZE; scanZ++) {
                for (int scanX = 0; scanX < SIZE; scanX++) {
                    if (!isFilled(mask, scanX, scanY, scanZ)) {
                        continue;
                    }

                    double centerX = (scanX + 0.5D) / SIZE;
                    double centerY = (scanY + 0.5D) / SIZE;
                    double centerZ = (scanZ + 0.5D) / SIZE;
                    double distance = distanceSq(centerX, centerY, centerZ, localX, localY, localZ);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[]{scanX, scanY, scanZ};
                    }
                }
            }
        }
        return best;
    }

    private static int[] findAlongImpactAxis(long[] mask, int x, int y, int z, Direction face) {
        int inwardX = -face.getStepX();
        int inwardY = -face.getStepY();
        int inwardZ = -face.getStepZ();
        int[] directHit = scanLine(mask, x, y, z, inwardX, inwardY, inwardZ);
        if (directHit != null) {
            return directHit;
        }

        Direction.Axis axis = face.getAxis();
        for (int offsetA = -1; offsetA <= 1; offsetA++) {
            for (int offsetB = -1; offsetB <= 1; offsetB++) {
                if (offsetA == 0 && offsetB == 0) {
                    continue;
                }

                int startX = x;
                int startY = y;
                int startZ = z;
                switch (axis) {
                    case X -> {
                        startY = Mth.clamp(y + offsetA, 0, SIZE - 1);
                        startZ = Mth.clamp(z + offsetB, 0, SIZE - 1);
                    }
                    case Y -> {
                        startX = Mth.clamp(x + offsetA, 0, SIZE - 1);
                        startZ = Mth.clamp(z + offsetB, 0, SIZE - 1);
                    }
                    case Z -> {
                        startX = Mth.clamp(x + offsetA, 0, SIZE - 1);
                        startY = Mth.clamp(y + offsetB, 0, SIZE - 1);
                    }
                }

                int[] nearbyHit = scanLine(mask, startX, startY, startZ, inwardX, inwardY, inwardZ);
                if (nearbyHit != null) {
                    return nearbyHit;
                }
            }
        }

        return null;
    }

    private static int[] scanLine(long[] mask, int startX, int startY, int startZ, int stepX, int stepY, int stepZ) {
        int x = startX;
        int y = startY;
        int z = startZ;
        for (int step = 0; step < SIZE; step++) {
            if (isFilled(mask, x, y, z)) {
                return new int[]{x, y, z};
            }
            x += stepX;
            y += stepY;
            z += stepZ;
            if (!isInside(x, y, z)) {
                break;
            }
        }
        return null;
    }

    private static void fillBox(long[] mask, AABB box) {
        int minX = toMinIndex(box.minX);
        int minY = toMinIndex(box.minY);
        int minZ = toMinIndex(box.minZ);
        int maxX = toMaxIndex(box.maxX);
        int maxY = toMaxIndex(box.maxY);
        int maxZ = toMaxIndex(box.maxZ);

        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return;
        }

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int index = index(x, y, z);
                    mask[index >> 6] |= 1L << (index & 63);
                }
            }
        }
    }

    private static int toMinIndex(double value) {
        return Mth.clamp((int) Math.floor(value * SIZE + SHAPE_EPSILON), 0, SIZE - 1);
    }

    private static int toMaxIndex(double value) {
        return Mth.clamp((int) Math.ceil(value * SIZE - SHAPE_EPSILON) - 1, 0, SIZE - 1);
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static VoxelShape buildCoarseCollisionShape(long[] mask) {
        boolean[] occupied = new boolean[COLLISION_GRID_SIZE * COLLISION_GRID_SIZE * COLLISION_GRID_SIZE];
        for (int cellY = 0; cellY < COLLISION_GRID_SIZE; cellY++) {
            for (int cellZ = 0; cellZ < COLLISION_GRID_SIZE; cellZ++) {
                for (int cellX = 0; cellX < COLLISION_GRID_SIZE; cellX++) {
                    if (hasFilledVoxelInCell(mask, cellX, cellY, cellZ)) {
                        occupied[coarseIndex(cellX, cellY, cellZ)] = true;
                    }
                }
            }
        }

        boolean[] consumed = new boolean[occupied.length];
        List<VoxelShape> boxes = new ArrayList<>();
        for (int y = 0; y < COLLISION_GRID_SIZE; y++) {
            for (int z = 0; z < COLLISION_GRID_SIZE; z++) {
                for (int x = 0; x < COLLISION_GRID_SIZE; x++) {
                    int index = coarseIndex(x, y, z);
                    if (consumed[index] || !occupied[index]) {
                        continue;
                    }

                    int maxX = x;
                    while (maxX + 1 < COLLISION_GRID_SIZE && canExpandCoarseX(occupied, consumed, maxX + 1, y, z)) {
                        maxX++;
                    }

                    int maxZ = z;
                    while (maxZ + 1 < COLLISION_GRID_SIZE && canExpandCoarseZ(occupied, consumed, x, maxX, y, maxZ + 1)) {
                        maxZ++;
                    }

                    int maxY = y;
                    while (maxY + 1 < COLLISION_GRID_SIZE && canExpandCoarseY(occupied, consumed, x, maxX, maxY + 1, z, maxZ)) {
                        maxY++;
                    }

                    markCoarseConsumed(consumed, x, maxX, y, maxY, z, maxZ);
                    boxes.add(box(
                            x * COLLISION_CELL_SIZE,
                            y * COLLISION_CELL_SIZE,
                            z * COLLISION_CELL_SIZE,
                            (maxX + 1) * COLLISION_CELL_SIZE,
                            (maxY + 1) * COLLISION_CELL_SIZE,
                            (maxZ + 1) * COLLISION_CELL_SIZE));
                }
            }
        }

        VoxelShape shape = Shapes.empty();
        for (VoxelShape box : boxes) {
            shape = Shapes.or(shape, box);
        }
        return shape;
    }

    private static boolean hasFilledVoxelInCell(long[] mask, int cellX, int cellY, int cellZ) {
        int baseX = cellX * COLLISION_CELL_SIZE;
        int baseY = cellY * COLLISION_CELL_SIZE;
        int baseZ = cellZ * COLLISION_CELL_SIZE;
        for (int y = baseY; y < baseY + COLLISION_CELL_SIZE; y++) {
            for (int z = baseZ; z < baseZ + COLLISION_CELL_SIZE; z++) {
                for (int x = baseX; x < baseX + COLLISION_CELL_SIZE; x++) {
                    if (isFilled(mask, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean canExpandCoarseX(boolean[] occupied, boolean[] consumed, int x, int y, int z) {
        int index = coarseIndex(x, y, z);
        return occupied[index] && !consumed[index];
    }

    private static boolean canExpandCoarseZ(boolean[] occupied, boolean[] consumed, int minX, int maxX, int y, int z) {
        for (int x = minX; x <= maxX; x++) {
            int index = coarseIndex(x, y, z);
            if (!occupied[index] || consumed[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean canExpandCoarseY(boolean[] occupied, boolean[] consumed, int minX, int maxX, int y, int minZ, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int index = coarseIndex(x, y, z);
                if (!occupied[index] || consumed[index]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void markCoarseConsumed(boolean[] consumed, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    consumed[coarseIndex(x, y, z)] = true;
                }
            }
        }
    }

    private static int coarseIndex(int x, int y, int z) {
        return (y * COLLISION_GRID_SIZE * COLLISION_GRID_SIZE) + (z * COLLISION_GRID_SIZE) + x;
    }

    private static boolean isInside(int x, int y, int z) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
    }

    private static VoxelShape box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return Shapes.box(
                minX / 16.0D,
                minY / 16.0D,
                minZ / 16.0D,
                maxX / 16.0D,
                maxY / 16.0D,
                maxZ / 16.0D);
    }

    private static int toVoxel(double value) {
        if (value >= 1.0D) {
            return SIZE - 1;
        }
        if (value < 0.0D) {
            return -1;
        }
        return (int) Math.floor(value * SIZE);
    }

    private static int sign(double value) {
        if (value > HIT_EPSILON) {
            return 1;
        }
        if (value < -HIT_EPSILON) {
            return -1;
        }
        return 0;
    }

    private static double initialBoundaryT(double start, double delta, int voxel, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double nextBoundary = step > 0 ? (voxel + 1) / (double) SIZE : voxel / (double) SIZE;
        return (nextBoundary - start) / delta;
    }

    private static double stepDelta(double delta) {
        if (Math.abs(delta) < HIT_EPSILON) {
            return Double.POSITIVE_INFINITY;
        }
        return 1.0D / (Math.abs(delta) * SIZE);
    }

    private static SegmentClip clipToUnitCube(Vec3 start, Vec3 end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;

        double entryT = 0.0D;
        double exitT = 1.0D;
        for (Direction.Axis axis : Direction.Axis.values()) {
            double origin = axis.choose(start.x, start.y, start.z);
            double delta = axis.choose(dx, dy, dz);
            if (Math.abs(delta) < HIT_EPSILON) {
                if (origin < 0.0D || origin > 1.0D) {
                    return null;
                }
                continue;
            }

            double inverseDelta = 1.0D / delta;
            double t1 = (0.0D - origin) * inverseDelta;
            double t2 = (1.0D - origin) * inverseDelta;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }

            entryT = Math.max(entryT, t1);
            exitT = Math.min(exitT, t2);
            if (entryT - exitT > HIT_EPSILON) {
                return null;
            }
        }

        if (exitT < 0.0D || entryT > 1.0D) {
            return null;
        }

        double clampedEntry = Math.max(0.0D, entryT);
        double clampedExit = Math.min(1.0D, exitT);
        if (clampedEntry - clampedExit > HIT_EPSILON) {
            return null;
        }

        Vec3 clippedStart = start.add(dx * clampedEntry, dy * clampedEntry, dz * clampedEntry);
        Vec3 clippedEnd = start.add(dx * clampedExit, dy * clampedExit, dz * clampedExit);
        return new SegmentClip(clippedStart, clippedEnd, clampedEntry, clampedExit);
    }

    private static double distanceSq(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x0 - x1;
        double dy = y0 - y1;
        double dz = z0 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    private record SegmentClip(Vec3 localStart, Vec3 localEnd, double entryT, double exitT) {
    }
}
