package com.realistic_bullets.init;

import com.realistic_bullets.RealisticBulletsMod;
import com.realistic_bullets.block.entity.DamagedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, RealisticBulletsMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<DamagedBlockEntity>> DAMAGED_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("damaged_block",
                    () -> BlockEntityType.Builder.of(DamagedBlockEntity::new, ModBlocks.DAMAGED_BLOCK.get()).build(null));

    private ModBlockEntities() {
    }
}
