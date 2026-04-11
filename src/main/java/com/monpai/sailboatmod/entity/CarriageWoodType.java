package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.resources.ResourceLocation;

public enum CarriageWoodType {
    OAK("oak"),
    SPRUCE("spruce"),
    DARK_OAK("dark_oak");

    private final String serializedName;

    CarriageWoodType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String translationKey() {
        return "item.sailboatmod.carriage.wood." + serializedName;
    }

    public ResourceLocation textureLocation() {
        return new ResourceLocation(SailboatMod.MODID, "textures/entity/carriage_" + serializedName + ".png");
    }

    public CarriageWoodType next() {
        CarriageWoodType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static CarriageWoodType fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return OAK;
        }
        for (CarriageWoodType type : values()) {
            if (type.serializedName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return OAK;
    }
}
