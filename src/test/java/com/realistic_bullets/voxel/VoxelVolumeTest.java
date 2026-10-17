package com.realistic_bullets.voxel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VoxelVolumeTest {
    @Test
    void traceReturnsNullThroughOpenTunnel() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;
        for (int x = 0; x < VoxelVolume.SIZE; x++) {
            VoxelVolume.clear(mask, x, y, z);
        }

        int[] impact = VoxelVolume.traceFirstFilledVoxel(
                mask,
                BlockPos.ZERO,
                new Vec3(-1.0D, center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)));

        assertNull(impact);
    }

    @Test
    void traceHitsFirstSolidVoxelAfterTunnel() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;
        for (int x = 0; x < 11; x++) {
            VoxelVolume.clear(mask, x, y, z);
        }

        int[] impact = VoxelVolume.traceFirstFilledVoxel(
                mask,
                BlockPos.ZERO,
                new Vec3(-1.0D, center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)));

        assertArrayEquals(new int[]{11, y, z}, impact);
    }

    @Test
    void traceHitsImmediateSurfaceOnSolidBlock() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;

        int[] impact = VoxelVolume.traceFirstFilledVoxel(
                mask,
                BlockPos.ZERO,
                new Vec3(-1.0D, center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)));

        assertArrayEquals(new int[]{0, y, z}, impact);
    }

    @Test
    void traceReturnsNullWhenStartingInsideOpenTunnel() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;
        for (int x = 0; x < VoxelVolume.SIZE; x++) {
            VoxelVolume.clear(mask, x, y, z);
        }

        int[] impact = VoxelVolume.traceFirstFilledVoxel(
                mask,
                BlockPos.ZERO,
                new Vec3(center(4), center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)));

        assertNull(impact);
    }

    @Test
    void traceShapeAllowsClipThroughSingleVoxelTunnel() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;
        for (int x = 0; x < VoxelVolume.SIZE; x++) {
            VoxelVolume.clear(mask, x, y, z);
        }

        VoxelShape shape = VoxelVolume.buildTraceShape(mask);
        BlockHitResult hit = shape.clip(
                new Vec3(-1.0D, center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)),
                BlockPos.ZERO);

        assertNull(hit);
    }

    @Test
    void traceShapeHitsInteriorWallAfterSingleVoxelTunnel() {
        long[] mask = fullMask();
        int y = 8;
        int z = 8;
        for (int x = 0; x < 11; x++) {
            VoxelVolume.clear(mask, x, y, z);
        }

        VoxelShape shape = VoxelVolume.buildTraceShape(mask);
        BlockHitResult hit = shape.clip(
                new Vec3(-1.0D, center(y), center(z)),
                new Vec3(2.0D, center(y), center(z)),
                BlockPos.ZERO);

        assertNotNull(hit);
        assertEquals(11.0D / VoxelVolume.SIZE, hit.getLocation().x, 1.0E-6D);
    }

    private static long[] fullMask() {
        long[] mask = VoxelVolume.emptyMask();
        for (int y = 0; y < VoxelVolume.SIZE; y++) {
            for (int z = 0; z < VoxelVolume.SIZE; z++) {
                for (int x = 0; x < VoxelVolume.SIZE; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    mask[index >> 6] |= 1L << (index & 63);
                }
            }
        }
        return mask;
    }

    private static double center(int voxel) {
        return (voxel + 0.5D) / VoxelVolume.SIZE;
    }
}
