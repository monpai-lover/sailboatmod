package com.monpai.sailboatmod.client.mixin;

import com.monpai.sailboatmod.client.chat.NationChatFlags;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentFlagMixin {
    @Inject(method="getLineHeight",at=@At("RETURN"),cancellable=true)
    private void sailboatmod$flagLineHeight(CallbackInfoReturnable<Integer> cir){
        if(NationChatFlags.active())cir.setReturnValue(Math.max(18,cir.getReturnValue()));
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),require=1)
    private int sailboatmod$flagLine(GuiGraphics g,Font font,FormattedCharSequence line,int x,int y,int color){
        int end=g.drawString(font,line,x,y,color);
        NationChatFlags.drawBadges(g,font,line,x,y,color);return end;
    }
}
