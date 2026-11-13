package dev.hearthkin.client;

import dev.hearthkin.Hearthkin;
import dev.hearthkin.entity.SettlerEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

public final class HearthkinClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Hearthkin.SETTLER, SettlerRenderer::new);
    }

    /** Renders settlers with the player model and a default skin chosen from their UUID. */
    static final class SettlerRenderer extends BipedEntityRenderer<SettlerEntity, PlayerEntityModel<SettlerEntity>> {
        SettlerRenderer(EntityRendererFactory.Context ctx) {
            super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        }

        @Override
        public Identifier getTexture(SettlerEntity entity) {
            return DefaultSkinHelper.getTexture(entity.getUuid());
        }
    }
}
