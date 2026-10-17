package com.realistic_bullets.client.model;

import com.realistic_bullets.block.entity.DamagedBlockEntity;
import com.realistic_bullets.voxel.DamageModelData;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DamagedBlockBakedModel extends BakedModelWrapper<BakedModel> {
    public DamagedBlockBakedModel(BakedModel originalModel) {
        super(originalModel);
    }

    @NotNull
    @Override
    public ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DamagedBlockEntity damagedBlockEntity) {
            return damagedBlockEntity.getModelData();
        }
        return ModelData.EMPTY;
    }

    @NotNull
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData extraData, @Nullable RenderType renderType) {
        DamageModelData modelData = extraData.get(DamagedBlockEntity.DAMAGE_MODEL_PROPERTY);
        if (modelData == null || modelData.isEmpty()) {
            return List.of();
        }

        DamageQuadModelCache.CompiledModel compiledModel = DamageQuadModelCache.getOrBuildModel(modelData);
        if (renderType != null && renderType != compiledModel.renderType()) {
            return List.of();
        }
        return compiledModel.quads(side);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        DamageModelData modelData = data.get(DamagedBlockEntity.DAMAGE_MODEL_PROPERTY);
        if (modelData == null || modelData.isEmpty()) {
            return ChunkRenderTypeSet.none();
        }
        return ChunkRenderTypeSet.of(DamageQuadModelCache.getOrBuildModel(modelData).renderType());
    }

    @Override
    public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        DamageModelData modelData = data.get(DamagedBlockEntity.DAMAGE_MODEL_PROPERTY);
        if (modelData == null || modelData.isEmpty()) {
            return super.getParticleIcon(data);
        }
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(modelData.originalState()).getParticleIcon(ModelData.EMPTY);
    }
}
