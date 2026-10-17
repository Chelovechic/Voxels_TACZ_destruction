package com.realistic_bullets.block;

import com.realistic_bullets.block.entity.DamagedBlockEntity;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class DamagedBlock extends BaseEntityBlock implements SimpleWaterloggedBlock, EntityBlock {
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final String TACZ_BULLET_CLASS_NAME = "com.tacz.guns.entity.EntityKineticBullet";

    public DamagedBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(WATERLOGGED, Boolean.FALSE));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DamagedBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getStoredShape(level, pos);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (isTaczBulletContext(context)) {
            return getBulletTraceShape(level, pos);
        }
        return getStoredShape(level, pos);
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getStoredShape(level, pos);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getStoredShape(level, pos);
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getRenderOcclusionShape(level, pos);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getStoredShape(level, pos);
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState originalState = getOriginalState(level, pos);
        if (originalState != null) {
            return originalState.propagatesSkylightDown(level, pos);
        }
        VoxelShape shape = getStoredShape(level, pos);
        return shape.isEmpty() || !Block.isShapeFullBlock(shape);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState originalState = getOriginalState(level, pos);
        if (originalState != null) {
            return originalState.getLightBlock(level, pos);
        }
        VoxelShape shape = getStoredShape(level, pos);
        if (shape.isEmpty()) {
            return 0;
        }
        return Block.isShapeFullBlock(shape) ? level.getMaxLightLevel() : 1;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState originalState = getOriginalState(level, pos);
        if (originalState != null) {
            return originalState.getShadeBrightness(level, pos);
        }
        return Block.isShapeFullBlock(getStoredShape(level, pos)) ? 0.2F : 1.0F;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY));
        if (damaged == null) {
            return Collections.emptyList();
        }

        BlockState originalState = damaged.getOriginalState();
        if (originalState.isAir()) {
            return Collections.emptyList();
        }

        builder.withOptionalParameter(LootContextParams.BLOCK_ENTITY, damaged.createOriginalBlockEntity());
        return originalState.getDrops(builder);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        if (damaged == null || damaged.getOriginalState().isAir()) {
            return ItemStack.EMPTY;
        }
        return damaged.getOriginalState().getBlock().getCloneItemStack(level, pos, damaged.getOriginalState());
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        if (damaged == null || damaged.getOriginalState().isAir()) {
            return super.getDestroyProgress(state, player, level, pos);
        }
        return damaged.getOriginalState().getDestroyProgress(player, level, pos);
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        if (damaged == null || damaged.getOriginalState().isAir()) {
            return super.getSoundType(state, level, pos, entity);
        }
        return damaged.getOriginalState().getSoundType(level, pos, entity);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        BlockState effectState = damaged == null ? state : damaged.getOriginalState();
        if (effectState.isAir()) {
            super.playerWillDestroy(level, pos, state, player);
            return;
        }

        level.levelEvent(player, 2001, pos, Block.getId(effectState));
        if (effectState.is(BlockTags.GUARDED_BY_PIGLINS)) {
            PiglinAi.angerNearbyPiglins(player, false);
        }
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, effectState));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    private static VoxelShape getStoredShape(BlockGetter level, BlockPos pos) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        return damaged == null ? Shapes.empty() : damaged.getCollisionShape();
    }

    private static VoxelShape getBulletTraceShape(BlockGetter level, BlockPos pos) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        return damaged == null ? Shapes.empty() : damaged.getBulletTraceShape();
    }

    private static VoxelShape getRenderOcclusionShape(BlockGetter level, BlockPos pos) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        return damaged == null ? Shapes.empty() : damaged.getBulletTraceShape();
    }

    @Nullable
    private static BlockState getOriginalState(BlockGetter level, BlockPos pos) {
        DamagedBlockEntity damaged = getDamagedBlockEntity(level.getBlockEntity(pos));
        if (damaged == null) {
            return null;
        }
        BlockState originalState = damaged.getOriginalState();
        return originalState.isAir() ? null : originalState;
    }

    private static boolean isTaczBulletContext(CollisionContext context) {
        if (!(context instanceof EntityCollisionContext entityContext)) {
            return false;
        }
        Entity entity = entityContext.getEntity();
        return entity != null && TACZ_BULLET_CLASS_NAME.equals(entity.getClass().getName());
    }

    @Nullable
    private static DamagedBlockEntity getDamagedBlockEntity(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof DamagedBlockEntity damaged ? damaged : null;
    }
}
