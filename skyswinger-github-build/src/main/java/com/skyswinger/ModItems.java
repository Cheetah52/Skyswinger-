package com.skyswinger;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Web Shooter and Web Cartridge items. */
public final class ModItems {
    public static final int SHOOTER_FLUID = 64; // web fluid a full Web Shooter holds

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SkySwinger.MODID);

    public static final DeferredItem<WebShooterItem> WEB_SHOOTER = ITEMS.register("web_shooter",
            () -> new WebShooterItem(new Item.Properties().durability(SHOOTER_FLUID)));
    public static final DeferredItem<Item> WEB_CARTRIDGE = ITEMS.registerSimpleItem("web_cartridge",
            new Item.Properties().stacksTo(16));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(ModItems::addToTabs);
    }

    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WEB_SHOOTER.get());
            event.accept(WEB_CARTRIDGE.get());
        }
    }

    private ModItems() {}
}
