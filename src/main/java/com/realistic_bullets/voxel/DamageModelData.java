package com.realistic_bullets.voxel;

import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DamageModelData {
    public static final DamageModelData EMPTY = new DamageModelData(Blocks.AIR.defaultBlockState(), VoxelVolume.emptyMask(), 0L, 0.0D, 0.0D, 0.0D);

    private final BlockState originalState;
    private final long[] voxels;
    private final long modelSeed;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final int hashCode;

    public DamageModelData(BlockState originalState, long[] voxels, long modelSeed, double offsetX, double offsetY, double offsetZ) {
        this.originalState = originalState;
        this.voxels = VoxelVolume.copy(voxels);
        this.modelSeed = modelSeed;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;

        int hash = 31 * this.originalState.hashCode() + Arrays.hashCode(this.voxels);
        hash = 31 * hash + Long.hashCode(this.modelSeed);
        hash = 31 * hash + Double.hashCode(this.offsetX);
        hash = 31 * hash + Double.hashCode(this.offsetY);
        hash = 31 * hash + Double.hashCode(this.offsetZ);
        this.hashCode = hash;
    }

    public BlockState originalState() {
        return originalState;
    }

    public long[] voxels() {
        return voxels;
    }

    public long modelSeed() {
        return modelSeed;
    }

    public double offsetX() {
        return offsetX;
    }

    public double offsetY() {
        return offsetY;
    }

    public double offsetZ() {
        return offsetZ;
    }

    public boolean isEmpty() {
        return originalState.isAir() || VoxelVolume.isEmpty(voxels);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DamageModelData other)) {
            return false;
        }
        return this.originalState.equals(other.originalState)
                && Arrays.equals(this.voxels, other.voxels)
                && this.modelSeed == other.modelSeed
                && Double.compare(this.offsetX, other.offsetX) == 0
                && Double.compare(this.offsetY, other.offsetY) == 0
                && Double.compare(this.offsetZ, other.offsetZ) == 0;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
