package com.monpai.sailboatmod.client.renderer.resident;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.service.ResidentSkinService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ResidentEntityRenderer extends HumanoidMobRenderer<ResidentEntity, HumanoidModel<ResidentEntity>> {
    private static final ResourceLocation DEFAULT_SKIN =
            new ResourceLocation(SailboatMod.MODID, "textures/entity/resident/default.png");
    private static final Map<String, ResourceLocation> SKIN_CACHE = new ConcurrentHashMap<>();

    public ResidentEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity entity) {
        String requestedSkin = ResidentSkinService.resolveSkinHash(
                entity.getSkinHash(),
                entity.getResidentId(),
                entity.getProfession(),
                entity.getGender()
        );
        String cacheKey = requestedSkin + "|" + entity.getProfession().id() + "|" + entity.getGender().id();
        return SKIN_CACHE.computeIfAbsent(cacheKey, key -> resolveTexture(entity, requestedSkin));
    }

    @Override
    protected boolean shouldShowName(ResidentEntity entity) {
        return !entity.getResidentName().isBlank();
    }

    private ResourceLocation resolveTexture(ResidentEntity entity, String requestedSkin) {
        for (String candidate : buildCandidates(entity, requestedSkin)) {
            ResourceLocation location = texture(candidate);
            if (Minecraft.getInstance().getResourceManager().getResource(location).isPresent()) {
                return location;
            }
        }
        return DEFAULT_SKIN;
    }

    private List<String> buildCandidates(ResidentEntity entity, String requestedSkin) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedSkin);

        String fallbackSkin = ResidentSkinService.chooseFallbackSkin(
                entity.getResidentId(),
                entity.getProfession(),
                entity.getGender()
        );
        candidates.add(fallbackSkin);

        addAlternateVariant(candidates, requestedSkin);
        addAlternateVariant(candidates, fallbackSkin);
        candidates.add("default");
        return new ArrayList<>(candidates);
    }

    private void addAlternateVariant(LinkedHashSet<String> candidates, String skin) {
        if (skin == null || skin.isBlank()) {
            return;
        }
        int underscore = skin.lastIndexOf('_');
        if (underscore < 0 || underscore == skin.length() - 1) {
            return;
        }
        String prefix = skin.substring(0, underscore + 1);
        String suffix = skin.substring(underscore + 1);
        try {
            int variant = Integer.parseInt(suffix);
            if (suffix.length() == 1) {
                candidates.add(prefix + "0" + variant);
            } else if (suffix.length() == 2 && suffix.startsWith("0")) {
                candidates.add(prefix + variant);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private ResourceLocation texture(String relativePath) {
        return new ResourceLocation(SailboatMod.MODID, "textures/entity/resident/" + relativePath + ".png");
    }
}
