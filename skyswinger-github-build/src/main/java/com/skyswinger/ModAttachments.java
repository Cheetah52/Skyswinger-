package com.skyswinger;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data saved on each player: have they been bitten (and accepted the powers)? Kept when you die. */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, SkySwinger.MODID);

    public static final Supplier<AttachmentType<Boolean>> BITTEN = TYPES.register("bitten",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    private ModAttachments() {}
}
