package dev.hearthkin;

import dev.hearthkin.command.HearthkinCommands;
import dev.hearthkin.entity.SettlerEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Hearthkin implements ModInitializer {
    public static final String MOD_ID = "hearthkin";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static final EntityType<SettlerEntity> SETTLER = Registry.register(
            Registries.ENTITY_TYPE,
            id("settler"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, SettlerEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(2)
                    .build());

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(SETTLER, SettlerEntity.createSettlerAttributes());
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> HearthkinCommands.register(dispatcher));
        LOG.info("Hearthkin spike loaded");
    }
}
