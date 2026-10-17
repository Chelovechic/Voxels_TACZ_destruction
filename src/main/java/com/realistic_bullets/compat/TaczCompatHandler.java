package com.realistic_bullets.compat;

import com.realistic_bullets.RealisticBulletsMod;
import com.realistic_bullets.block.DamagedBlock;
import com.realistic_bullets.block.entity.DamagedBlockEntity;
import com.realistic_bullets.init.ModBlocks;
import com.realistic_bullets.server.DamageSyncQueue;
import com.realistic_bullets.voxel.VoxelVolume;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public final class TaczCompatHandler {
    private static final String TACZ_MOD_ID = "tacz";
    private static final String TACZ_EVENT_CLASS_NAME = "com.tacz.guns.api.event.server.AmmoHitBlockEvent";
    private static final Map<Class<?>, EventAccessors> ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final TagKey<Block> NO_VOXEL_DAMAGE = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.tryBuild(RealisticBulletsMod.MOD_ID, "no_voxel_damage"));

    private TaczCompatHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onAnyForgeEvent(Event event) {
        if (!ModList.get().isLoaded(TACZ_MOD_ID) || !TACZ_EVENT_CLASS_NAME.equals(event.getClass().getName())) {
            return;
        }

        try {
            handleAmmoHitBlock(event);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RealisticBulletsMod.LOGGER.error("Failed to handle TACZ ammo hit event.", exception);
        }
    }

    private static void handleAmmoHitBlock(Event event) throws ReflectiveOperationException {
        EventAccessors accessors = ACCESSOR_CACHE.computeIfAbsent(event.getClass(), TaczCompatHandler::createAccessors);
        Object levelObject = accessors.levelGetter.invoke(event);
        Object hitResultObject = accessors.hitResultGetter.invoke(event);
        Object stateObject = accessors.stateGetter.invoke(event);
        Object ammoObject = accessors.ammoGetter.invoke(event);

        if (!(levelObject instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!(hitResultObject instanceof BlockHitResult hitResult) || !(stateObject instanceof BlockState eventState)) {
            return;
        }
        if (shouldIgnoreState(eventState)) {
            return;
        }
        Entity ammoEntity = ammoObject instanceof Entity entity ? entity : null;

        BlockPos pos = hitResult.getBlockPos();
        BlockState currentState = level.getBlockState(pos);
        if (currentState.isAir()) {
            return;
        }
        if (shouldIgnoreState(currentState)) {
            return;
        }
        if (currentState.getBlock() != eventState.getBlock() && currentState.getBlock() != ModBlocks.DAMAGED_BLOCK.get()) {
            return;
        }
        if (currentState.getBlock() == ModBlocks.DAMAGED_BLOCK.get()) {
            damageExistingBlock(event, level, pos, hitResult, ammoEntity);
            return;
        }

        long[] voxels = VoxelVolume.createMask(level, pos, eventState);
        if (VoxelVolume.isEmpty(voxels)) {
            return;
        }

        int[] impactedVoxel = findInitialImpactVoxel(voxels, pos, hitResult, ammoEntity);
        if (impactedVoxel == null || !VoxelVolume.clear(voxels, impactedVoxel[0], impactedVoxel[1], impactedVoxel[2])) {
            return;
        }

        if (VoxelVolume.isEmpty(voxels)) {
            clearToFluidOrAir(level, pos);
            return;
        }

        CompoundTag originalBlockEntityData = readOriginalBlockEntity(currentState, level.getBlockEntity(pos));
        BlockState damagedState = ModBlocks.DAMAGED_BLOCK.get().defaultBlockState()
                .setValue(DamagedBlock.WATERLOGGED, isWater(level.getFluidState(pos)));
        level.setBlock(pos, damagedState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DamagedBlockEntity damagedBlockEntity) {
            damagedBlockEntity.initialize(eventState, originalBlockEntityData, voxels);
            damagedBlockEntity.sync();
            DamageSyncQueue.enqueue(level, pos);
        }
    }

    private static void damageExistingBlock(Event event, Level level, BlockPos pos, BlockHitResult hitResult, @Nullable Entity ammoEntity) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof DamagedBlockEntity damagedBlockEntity)) {
            return;
        }
        if (shouldIgnoreState(damagedBlockEntity.getOriginalState())) {
            clearToFluidOrAir(level, pos);
            cancelEvent(event);
            return;
        }

        int[] impactedVoxel = findDamagedBlockImpactVoxel(damagedBlockEntity.getVoxels(), pos, hitResult, ammoEntity);
        if (impactedVoxel == null) {
            cancelEvent(event);
            return;
        }
        if (!damagedBlockEntity.clearVoxel(impactedVoxel[0], impactedVoxel[1], impactedVoxel[2])) {
            return;
        }

        if (damagedBlockEntity.isCompletelyDestroyed()) {
            clearToFluidOrAir(level, pos);
        } else {
            damagedBlockEntity.sync();
            DamageSyncQueue.enqueue(level, pos);
        }
    }

    private static void clearToFluidOrAir(Level level, BlockPos pos) {
        level.setBlock(pos, isWater(level.getFluidState(pos)) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static boolean shouldIgnoreState(BlockState state) {
        Block block = state.getBlock();
        return state.is(NO_VOXEL_DAMAGE)
                || block instanceof AbstractGlassBlock
                || block instanceof StainedGlassPaneBlock
                || state.is(Blocks.GLASS_PANE)
                || block instanceof IronBarsBlock && state.instrument() == NoteBlockInstrument.HAT
                || isGlassNamedBlock(block);
    }

    private static boolean isGlassNamedBlock(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.contains("glass") || path.contains("pane");
    }

    private static boolean isWater(FluidState fluidState) {
        return fluidState.is(FluidTags.WATER);
    }

    @Nullable
    private static CompoundTag readOriginalBlockEntity(BlockState currentState, @Nullable BlockEntity blockEntity) {
        if (blockEntity != null && currentState.hasBlockEntity()) {
            return blockEntity.saveWithId();
        }
        return null;
    }

    private static EventAccessors createAccessors(Class<?> eventClass) {
        try {
            return new EventAccessors(
                    eventClass.getMethod("getLevel"),
                    eventClass.getMethod("getHitResult"),
                    eventClass.getMethod("getState"),
                    eventClass.getMethod("getAmmo"));
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Unexpected TACZ event shape for " + eventClass.getName(), exception);
        }
    }

    private static int[] findInitialImpactVoxel(long[] voxels, BlockPos pos, BlockHitResult hitResult, @Nullable Entity ammoEntity) {
        int[] tracedImpact = traceAmmoImpactVoxel(voxels, pos, ammoEntity);
        if (tracedImpact != null) {
            return tracedImpact;
        }
        return VoxelVolume.findImpactVoxel(voxels, pos, hitResult.getLocation(), hitResult.getDirection());
    }

    @Nullable
    private static int[] findDamagedBlockImpactVoxel(long[] voxels, BlockPos pos, BlockHitResult hitResult, @Nullable Entity ammoEntity) {
        int[] tracedImpact = traceAmmoImpactVoxel(voxels, pos, ammoEntity);
        if (tracedImpact != null || ammoEntity != null) {
            return tracedImpact;
        }
        return VoxelVolume.findImpactVoxel(voxels, pos, hitResult.getLocation(), hitResult.getDirection());
    }

    @Nullable
    private static int[] traceAmmoImpactVoxel(long[] voxels, BlockPos pos, @Nullable Entity ammoEntity) {
        if (ammoEntity == null) {
            return null;
        }
        return VoxelVolume.traceFirstFilledVoxel(
                voxels,
                pos,
                ammoEntity.position(),
                ammoEntity.position().add(ammoEntity.getDeltaMovement()));
    }

    private static void cancelEvent(Event event) {
        if (event.isCancelable()) {
            event.setCanceled(true);
        }
    }

    private record EventAccessors(Method levelGetter, Method hitResultGetter, Method stateGetter, Method ammoGetter) {
    }
}
