package com.monpai.sailboatmod.client.chat;
import com.monpai.sailboatmod.nation.chat.ChatFlagBadge;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatFlagDecorationTest {
    @Test void insertsOneSpacerBeforeNationPrefix(){
        String badge=new ChatFlagBadge("abcdef",0xffffff,0,false).encode();
        var msg=Component.literal("<").append(Component.literal("[国][皇帝]").withStyle(Style.EMPTY.withInsertion(badge)))
            .append(Component.literal("Player> hello"));
        var changed=NationChatFlags.decorate(msg);assertEquals("<\ue000[国][皇帝]Player> hello",changed.getString());
        assertEquals("<[国][皇帝]Player> hello",msg.getString());
    }
    @Test void ordinaryChatIsUntouched(){var msg=Component.literal("hello");assertSame(msg,NationChatFlags.decorate(msg));}
    @Test void malformedStyleCannotLoadArbitraryTexture(){
        var msg=Component.literal("hi").withStyle(Style.EMPTY.withInsertion("sailboatmod:flag:../x:0:0:0"));assertSame(msg,NationChatFlags.decorate(msg));
    }
}
