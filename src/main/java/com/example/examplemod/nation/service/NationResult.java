package com.example.examplemod.nation.service;

import net.minecraft.network.chat.Component;

public record NationResult(boolean success, Component message) {
    public static NationResult success(Component message) {
        return new NationResult(true, message);
    }

    public static NationResult failure(Component message) {
        return new NationResult(false, message);
    }
}
