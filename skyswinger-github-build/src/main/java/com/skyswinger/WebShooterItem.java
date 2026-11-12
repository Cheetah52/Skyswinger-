package com.skyswinger;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Wrist gadget. In survival you need one in your inventory to use web powers.
 * Its durability bar is your web fluid: web shots use it up. Right-click to refill with a Web Cartridge.
 */
public class WebShooterItem extends Item {
    public static final int REFILL = 32; // fluid per cartridge

    public WebShooterItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getDamageValue() <= 0) return InteractionResultHolder.pass(stack); // already full

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack c = inv.getItem(i);
            if (!c.is(ModItems.WEB_CARTRIDGE.get())) continue;
            if (!level.isClientSide()) {
                if (!player.isCreative()) c.shrink(1);
                stack.setDamageValue(Math.max(0, stack.getDamageValue() - REFILL));
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.7f, 1.4f);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.fail(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        int fluid = stack.getMaxDamage() - stack.getDamageValue();
        lines.add(Component.literal("Web fluid: " + fluid + " / " + stack.getMaxDamage()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Right-click to refill with a Web Cartridge").withStyle(ChatFormatting.DARK_GRAY));
    }
}
