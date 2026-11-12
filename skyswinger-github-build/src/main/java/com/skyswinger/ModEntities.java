package com.skyswinger;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Strange Spider mob. Where it spawns is in data/skyswinger/neoforge/biome_modifier/strange_spider.json. */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SkySwinger.MODID);

    public static final Supplier<EntityType<StrangeSpider>> STRANGE_SPIDER = TYPES.register("strange_spider",
            () -> EntityType.Builder.of(StrangeSpider::new, MobCategory.MONSTER)
                    .sized(0.5f, 0.35f)
                    .clientTrackingRange(8)
                    .build("strange_spider"));

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener(ModEntities::attributes);
        modBus.addListener(ModEntities::spawnRules);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(STRANGE_SPIDER.get(), StrangeSpider.createAttributes().build());
    }

    private static void spawnRules(RegisterSpawnPlacementsEvent event) {
        event.register(STRANGE_SPIDER.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    private ModEntities() {}
}
