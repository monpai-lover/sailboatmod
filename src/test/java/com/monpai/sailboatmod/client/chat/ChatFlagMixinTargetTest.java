package com.monpai.sailboatmod.client.chat;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
/** Verify injection targets against the actual 1.21.1 dependency bytecode, not mocks. */
class ChatFlagMixinTargetTest {
    @Test void exactChatRenderInvocationAndHeightMethodExist() throws Exception {
        var height=new AtomicBoolean();var draw=new AtomicBoolean();
        try(var input=getClass().getClassLoader().getResourceAsStream("net/minecraft/client/gui/components/ChatComponent.class")){
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    if(name.equals("getLineHeight")&&descriptor.equals("()I"))height.set(true);
                    if(!name.equals("render"))return null;
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){
                            if(owner.equals("net/minecraft/client/gui/GuiGraphics")&&method.equals("drawString")
                               &&desc.equals("(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))draw.set(true);
                        }
                    };
                }
            },0);
        }
        assertTrue(height.get(),"chat line-height target");assertTrue(draw.get(),"formatted-line drawString injection target");
    }
}
