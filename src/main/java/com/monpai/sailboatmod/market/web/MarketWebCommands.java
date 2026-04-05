package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CopyMarketWebTokenPacket;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

public final class MarketWebCommands {
    private MarketWebCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("marketweb")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("token")
                        .executes(context -> issueToken(context.getSource()))));
    }

    private static int issueToken(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        MarketWebServer server = MarketWebServer.get();
        if (server == null || !server.isRunning()) {
            source.sendFailure(Component.literal("Market web service is not running."));
            return 0;
        }
        String token = server.auth().issueLoginToken(player, com.monpai.sailboatmod.ModConfig.marketWebLoginTokenTtlMinutes());
        String host = com.monpai.sailboatmod.ModConfig.marketWebBindHost();
        String urlHost = "0.0.0.0".equals(host) ? "127.0.0.1" : host;
        String url = "http://" + urlHost + ":" + com.monpai.sailboatmod.ModConfig.marketWebPort() + "/";
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CopyMarketWebTokenPacket(token, url));
        source.sendSuccess(() -> Component.literal("Market web URL: ")
                .append(Component.literal(url).setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)))), false);
        source.sendSuccess(() -> Component.literal("Fixed login token copied to clipboard."), false);
        source.sendSuccess(() -> Component.literal("Fallback token: ")
                .append(Component.literal(token).setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token)))), false);
        return 1;
    }
}
