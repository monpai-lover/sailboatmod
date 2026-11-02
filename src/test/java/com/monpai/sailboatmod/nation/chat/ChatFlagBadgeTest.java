package com.monpai.sailboatmod.nation.chat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatFlagBadgeTest {
    @Test void metadataRoundTrip(){var b=new ChatFlagBadge("5b13d45b",0xabcdef,0x123456,true);assertEquals(b,ChatFlagBadge.parse(b.encode()).orElseThrow());}
    @Test void rejectsPaths(){assertTrue(ChatFlagBadge.parse("sailboatmod:flag:../evil:ffffff:ffffff:0").isEmpty());}
    @Test void rejectsMalformed(){assertTrue(ChatFlagBadge.parse(null).isEmpty());assertTrue(ChatFlagBadge.parse("sailboatmod:flag:ok:zzzz:0:1").isEmpty());assertTrue(ChatFlagBadge.parse("sailboatmod:flag:ok:0:0:yes").isEmpty());}
    @Test void titlesSupportChineseAndSpaces(){assertTrue(ChatFlagBadge.validTitle("海军统帅",16));assertTrue(ChatFlagBadge.validTitle("First Officer",16));}
    @Test void titlesRejectFormatSpoofing(){for(String s:new String[]{"§cAdmin","[Admin]","x\ny","x\u202Ey","", "\ue000"})assertFalse(ChatFlagBadge.validTitle(s,16),s);}
    @Test void titleLimitCountsCodepoints(){assertTrue(ChatFlagBadge.validTitle("海".repeat(16),16));assertFalse(ChatFlagBadge.validTitle("海".repeat(17),16));}
}
