package com.realistic_bullets;

import com.mojang.logging.LogUtils;
import com.realistic_bullets.client.model.DamagedBlockBakedModel;
import com.realistic_bullets.client.model.DamageQuadModelCache;
import com.realistic_bullets.compat.TaczCompatHandler;
import com.realistic_bullets.init.ModBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(RealisticBulletsMod.MOD_ID)
public class RealisticBulletsMod {
    public static final String MOD_ID = "realistic_bullets";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RealisticBulletsMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        com.realistic_bullets.init.ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(TaczCompatHandler.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("tacz")) {
                LOGGER.info("TACZ compatibility enabled.");
            } else {
                LOGGER.info("TACZ was not found. realistic_bullets will stay idle until TACZ is present.");
            }
        });
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void wrapDamagedBlockModel(ModelEvent.ModifyBakingResult event) {
            for (var state : ModBlocks.DAMAGED_BLOCK.get().getStateDefinition().getPossibleStates()) {
                var location = BlockModelShaper.stateToModelLocation(state);
                BakedModel bakedModel = event.getModels().get(location);
                if (bakedModel != null) {
                    event.getModels().put(location, new DamagedBlockBakedModel(bakedModel));
                }
            }
        }

        @SubscribeEvent
        public static void clearModelCaches(ModelEvent.BakingCompleted event) {
            DamageQuadModelCache.clearCaches();
        }
    }
}
