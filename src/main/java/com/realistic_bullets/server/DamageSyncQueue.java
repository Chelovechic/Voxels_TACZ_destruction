package com.realistic_bullets.server;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = com.realistic_bullets.RealisticBulletsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DamageSyncQueue {
    private static final Map<Level, LongOpenHashSet> DIRTY_BLOCKS = new ConcurrentHashMap<>();

    private DamageSyncQueue() {
    }

    public static void enqueue(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        DIRTY_BLOCKS.computeIfAbsent(level, ignored -> new LongOpenHashSet()).add(pos.asLong());
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide()) {
            return;
        }

        LongOpenHashSet dirtyPositions = DIRTY_BLOCKS.get(event.level);
        if (dirtyPositions == null || dirtyPositions.isEmpty()) {
            return;
        }

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        LongIterator iterator = dirtyPositions.iterator();
        while (iterator.hasNext()) {
            mutablePos.set(BlockPos.of(iterator.nextLong()));
            if (!event.level.hasChunkAt(mutablePos)) {
                continue;
            }

            var state = event.level.getBlockState(mutablePos);
            event.level.sendBlockUpdated(mutablePos, state, state, Block.UPDATE_CLIENTS);
        }

        dirtyPositions.clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        DIRTY_BLOCKS.remove(event.getLevel());
    }
}
