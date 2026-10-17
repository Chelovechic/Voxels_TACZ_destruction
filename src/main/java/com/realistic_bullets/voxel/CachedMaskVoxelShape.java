package com.realistic_bullets.voxel;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CachedMaskVoxelShape extends ArrayVoxelShape {
    private static final double[] COORDS = buildCoords();
    private final List<AABB> cachedBoxes;

    private CachedMaskVoxelShape(BitSetDiscreteVoxelShape shape, List<AABB> cachedBoxes) {
        super(shape, COORDS, COORDS, COORDS);
        this.cachedBoxes = cachedBoxes;
    }

    public static VoxelShape create(BitSetDiscreteVoxelShape shape) {
        if (shape.isEmpty()) {
            return Shapes.empty();
        }

        List<AABB> boxes = new ArrayList<>();
        double scale = 1.0D / VoxelVolume.SIZE;
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> boxes.add(new AABB(
                minX * scale,
                minY * scale,
                minZ * scale,
                maxX * scale,
                maxY * scale,
                maxZ * scale)), true);
        return new CachedMaskVoxelShape(shape, List.copyOf(boxes));
    }

    @Override
    public List<AABB> toAabbs() {
        return this.cachedBoxes;
    }

    @Nullable
    @Override
    public BlockHitResult clip(Vec3 start, Vec3 end, BlockPos pos) {
        if (this.isEmpty()) {
            return null;
        }

        Vec3 delta = end.subtract(start);
        if (delta.lengthSqr() < 1.0E-7D) {
            return null;
        }

        Vec3 sample = start.add(delta.scale(0.001D));
        if (this.shape.isFullWide(
                this.findIndex(Direction.Axis.X, sample.x - pos.getX()),
                this.findIndex(Direction.Axis.Y, sample.y - pos.getY()),
                this.findIndex(Direction.Axis.Z, sample.z - pos.getZ()))) {
            return new BlockHitResult(sample, Direction.getNearest(delta.x, delta.y, delta.z).getOpposite(), pos, true);
        }

        return AABB.clip(this.cachedBoxes, start, end, pos);
    }

    @Override
    public VoxelShape optimize() {
        return this;
    }

    private static double[] buildCoords() {
        double[] coords = new double[VoxelVolume.SIZE + 1];
        for (int i = 0; i <= VoxelVolume.SIZE; i++) {
            coords[i] = i / (double) VoxelVolume.SIZE;
        }
        return coords;
    }
}
