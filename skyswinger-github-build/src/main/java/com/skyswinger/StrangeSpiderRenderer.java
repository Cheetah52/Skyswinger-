package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.resources.ResourceLocation;

/** Looks like a tiny cave spider, glowing and sparking. */
public class StrangeSpiderRenderer extends SpiderRenderer<StrangeSpider> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/spider/cave_spider.png");

    public StrangeSpiderRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.3f;
    }

    @Override
    protected void scale(StrangeSpider entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.5f, 0.5f, 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(StrangeSpider entity) {
        return TEXTURE;
    }
}
