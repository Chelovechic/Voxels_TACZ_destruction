package com.realistic_bullets.block.entity;

import com.realistic_bullets.init.ModBlockEntities;
import com.realistic_bullets.voxel.DamageModelData;
import com.realistic_bullets.voxel.VoxelVolume;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;

public class DamagedBlockEntity extends BlockEntity {
    private static final String ORIGINAL_STATE_TAG = "OriginalState";
    private static final String ORIGINAL_BLOCK_ENTITY_TAG = "OriginalBlockEntity";
    private static final String VOXELS_TAG = "Voxels";
    public static final ModelProperty<DamageModelData> DAMAGE_MODEL_PROPERTY = new ModelProperty<>();

    private BlockState originalState = Blocks.AIR.defaultBlockState();
    @Nullable
    private CompoundTag originalBlockEntityData;
    private long[] voxels = VoxelVolume.emptyMask();
    private int filledVoxelCount;
    private VoxelShape cachedShape = Shapes.empty();
    private VoxelShape cachedTraceShape = Shapes.empty();
    private boolean shapeDirty = true;
    private boolean traceShapeDirty = true;
    private volatile DamageModelData renderData = DamageModelData.EMPTY;
    private volatile ModelData modelData = ModelData.EMPTY;

    public DamagedBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DAMAGED_BLOCK_ENTITY.get(), pos, state);
    }

    public void initialize(BlockState originalState, @Nullable CompoundTag originalBlockEntityData, long[] voxels) {
        this.originalState = originalState;
        this.originalBlockEntityData = originalBlockEntityData == null ? null : originalBlockEntityData.copy();
        this.voxels = VoxelVolume.copy(voxels);
        this.filledVoxelCount = VoxelVolume.countFilled(this.voxels);
        this.refreshDerivedState();
    }

    public BlockState getOriginalState() {
        return originalState;
    }

    public long[] getVoxels() {
        return voxels;
    }

    public boolean clearVoxel(int x, int y, int z) {
        if (!VoxelVolume.clear(this.voxels, x, y, z)) {
            return false;
        }
        this.filledVoxelCount--;
        this.refreshDerivedState();
        return true;
    }

    public boolean isCompletelyDestroyed() {
        return this.filledVoxelCount <= 0;
    }

    public VoxelShape getCollisionShape() {
        if (shapeDirty) {
            cachedShape = VoxelVolume.buildShape(this.voxels);
            shapeDirty = false;
        }
        return cachedShape;
    }

    public VoxelShape getBulletTraceShape() {
        if (traceShapeDirty) {
            cachedTraceShape = VoxelVolume.buildTraceShape(this.voxels);
            traceShapeDirty = false;
        }
        return cachedTraceShape;
    }

    public DamageModelData getRenderData() {
        return this.renderData;
    }

    @Nullable
    public BlockEntity createOriginalBlockEntity() {
        if (originalBlockEntityData == null || !originalState.hasBlockEntity()) {
            return null;
        }
        return BlockEntity.loadStatic(this.getBlockPos(), originalState, originalBlockEntityData.copy());
    }

    public void sync() {
        this.setChanged();
        Level level = this.getLevel();
        if (level != null) {
            level.blockEntityChanged(this.getBlockPos());
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        this.refreshDerivedState();
    }

    @NotNull
    @Override
    public ModelData getModelData() {
        return this.modelData;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        HolderGetter<net.minecraft.world.level.block.Block> blockLookup = this.level != null
                ? this.level.holderLookup(Registries.BLOCK)
                : BuiltInRegistries.BLOCK.asLookup();

        if (tag.contains(ORIGINAL_STATE_TAG)) {
            this.originalState = NbtUtils.readBlockState(blockLookup, tag.getCompound(ORIGINAL_STATE_TAG));
        } else {
            this.originalState = Blocks.AIR.defaultBlockState();
        }

        this.originalBlockEntityData = tag.contains(ORIGINAL_BLOCK_ENTITY_TAG) ? tag.getCompound(ORIGINAL_BLOCK_ENTITY_TAG).copy() : null;
        this.voxels = VoxelVolume.copy(tag.getLongArray(VOXELS_TAG));
        this.filledVoxelCount = VoxelVolume.countFilled(this.voxels);
        this.refreshDerivedState();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(ORIGINAL_STATE_TAG, NbtUtils.writeBlockState(this.originalState));
        tag.putLongArray(VOXELS_TAG, this.voxels);
        if (this.originalBlockEntityData != null) {
            tag.put(ORIGINAL_BLOCK_ENTITY_TAG, this.originalBlockEntityData.copy());
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void refreshDerivedState() {
        this.shapeDirty = true;
        this.traceShapeDirty = true;
        this.cachedShape = Shapes.empty();
        this.cachedTraceShape = Shapes.empty();

        DamageModelData updatedRenderData = DamageModelData.EMPTY;
        if (!this.originalState.isAir()) {
            long modelSeed = this.originalState.getSeed(this.worldPosition);
            Vec3 offset = this.level == null ? Vec3.ZERO : this.originalState.getOffset(this.level, this.worldPosition);
            updatedRenderData = new DamageModelData(this.originalState, this.voxels, modelSeed, offset.x, offset.y, offset.z);
        }

        this.renderData = updatedRenderData;
        this.modelData = updatedRenderData.isEmpty()
                ? ModelData.EMPTY
                : ModelData.builder().with(DAMAGE_MODEL_PROPERTY, updatedRenderData).build();
        this.requestModelDataUpdate();
    }
}
