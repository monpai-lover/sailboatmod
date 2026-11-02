package com.monpai.sailboatmod.client.chat;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.texture.NationFlagTextureCache;
import com.monpai.sailboatmod.nation.chat.ChatFlagBadge;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import java.util.Optional;

/** Chat-only decoration: preserves body/signature and all text styles, inserts one indivisible 18px spacer. */
@EventBusSubscriber(modid=SailboatMod.MODID,value=Dist.CLIENT)
public final class NationChatFlags {
    public static final ResourceLocation SPACER_FONT=ResourceLocation.fromNamespaceAndPath(SailboatMod.MODID,"chat_flag_space");
    private static boolean active;
    private NationChatFlags(){}
    public static boolean active(){return active;}
    @SubscribeEvent public static void received(ClientChatReceivedEvent.Player event){
        Component decorated=decorate(event.getMessage());
        if(decorated!=event.getMessage()){active=true;event.setMessage(decorated);}
    }
    public static Component decorate(Component message){
        var result=Component.empty();boolean[] added={false};
        message.visit((style,text)->{
            Optional<ChatFlagBadge> badge=ChatFlagBadge.parse(style.getInsertion());
            if(!added[0]&&badge.isPresent()){
                result.append(Component.literal("\ue000").setStyle(Style.EMPTY.withFont(SPACER_FONT)
                        .withInsertion(badge.get().encode()).withColor(0xffffff)));
                added[0]=true;
            }
            result.append(Component.literal(text).setStyle(badge.isPresent()?style.withInsertion(null):style));
            return Optional.empty();
        },Style.EMPTY);
        return added[0]?result:message;
    }
    /** Invoked after vanilla's text draw for this exact wrapped line, with vanilla scroll/scale/fade already applied. */
    public static void drawBadges(GuiGraphics graphics,Font font,FormattedCharSequence line,int x,int y,int color){
        var prefix=Component.empty();boolean[] painted={false};
        line.accept((index,style,codePoint)->{
            if(!painted[0]&&codePoint==0xE000&&SPACER_FONT.equals(style.getFont())){
                ChatFlagBadge.parse(style.getInsertion()).ifPresent(badge->{
                    int left=x+font.width(prefix)+1;
                    ResourceLocation texture=NationFlagTextureCache.resolveChatThumbnail(badge.flagId(),badge.primary(),badge.secondary(),badge.mirrored());
                    graphics.flush();float[] old=RenderSystem.getShaderColor().clone();
                    RenderSystem.setShaderColor(1,1,1,((color>>>24)&255)/255f);
                    try{graphics.blit(texture,left,y-4,16,16,0,0,16,16,16,16);}
                    finally{graphics.flush();RenderSystem.setShaderColor(old[0],old[1],old[2],old[3]);}
                });
                painted[0]=true;
                return false;
            }
            prefix.append(Component.literal(new String(Character.toChars(codePoint))).setStyle(style));return true;
        });
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){
        active=false;NationFlagTextureCache.clearChatThumbnails();
    }
}
