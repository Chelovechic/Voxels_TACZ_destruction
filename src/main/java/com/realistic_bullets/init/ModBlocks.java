package com.realistic_bullets.init;

import com.realistic_bullets.RealisticBulletsMod;
import com.realistic_bullets.block.DamagedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, RealisticBulletsMod.MOD_ID);

    public static final RegistryObject<Block> DAMAGED_BLOCK = BLOCKS.register("damaged_block",
            () -> new DamagedBlock(BlockBehaviour.Properties.of()
                    .strength(0.5F, 6.0F)
                    .dynamicShape()
                    .noOcclusion()));

    private ModBlocks() {
    }
}
