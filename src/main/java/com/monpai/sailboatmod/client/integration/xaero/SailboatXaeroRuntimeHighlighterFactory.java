package com.monpai.sailboatmod.client.integration.xaero;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;

final class SailboatXaeroRuntimeHighlighterFactory implements Opcodes {
    private static final String PACKAGE_INTERNAL = "com/monpai/sailboatmod/client/integration/xaero/";
    private static final String BRIDGE_INTERNAL = PACKAGE_INTERNAL + "SailboatXaeroHighlighterBridge";
    private static final String RESOURCE_KEY_DESC = "Lnet/minecraft/resources/ResourceKey;";
    private static final String COMPONENT_DESC = "Lnet/minecraft/network/chat/Component;";

    private static final String MINIMAP_CLASS = "com.monpai.sailboatmod.client.integration.xaero.SailboatXaeroMinimapHighlighter";
    private static final String MINIMAP_INTERNAL = PACKAGE_INTERNAL + "SailboatXaeroMinimapHighlighter";
    private static final String MINIMAP_SUPER = "xaero/common/minimap/highlight/ChunkHighlighter";
    private static final String MINIMAP_COMPILER_DESC = "Lxaero/hud/minimap/info/render/compile/InfoDisplayCompiler;";

    private static final String WORLD_MAP_CLASS = "com.monpai.sailboatmod.client.integration.xaero.SailboatXaeroWorldMapHighlighter";
    private static final String WORLD_MAP_INTERNAL = PACKAGE_INTERNAL + "SailboatXaeroWorldMapHighlighter";
    private static final String WORLD_MAP_SUPER = "xaero/map/highlight/ChunkHighlighter";

    static Object createMinimapHighlighter() throws ReflectiveOperationException {
        return loadOrDefine(MINIMAP_CLASS, MINIMAP_INTERNAL, MINIMAP_SUPER, true)
                .getDeclaredConstructor()
                .newInstance();
    }

    static Object createWorldMapHighlighter() throws ReflectiveOperationException {
        return loadOrDefine(WORLD_MAP_CLASS, WORLD_MAP_INTERNAL, WORLD_MAP_SUPER, false)
                .getDeclaredConstructor()
                .newInstance();
    }

    private static Class<?> loadOrDefine(String className, String internalName, String superInternal, boolean minimap)
            throws ReflectiveOperationException {
        ClassLoader loader = SailboatXaeroRuntimeHighlighterFactory.class.getClassLoader();
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException ignored) {
            byte[] bytes = minimap
                    ? buildMinimapHighlighter(internalName, superInternal)
                    : buildWorldMapHighlighter(internalName, superInternal);
            return MethodHandles.lookup().defineClass(bytes);
        }
    }

    private static byte[] buildMinimapHighlighter(String internalName, String superInternal) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, superInternal, null);
        writeConstructor(writer, superInternal);
        writeBooleanDelegate(writer, "regionHasHighlights");
        writeBooleanDelegate(writer, "chunkIsHighlit");
        writeColorsDelegate(writer);
        writeMinimapTooltipDelegate(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildWorldMapHighlighter(String internalName, String superInternal) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, superInternal, null);
        writeConstructor(writer, superInternal);
        writeIntDelegate(writer, "calculateRegionHash");
        writeBooleanDelegate(writer, "regionHasHighlights");
        writeBooleanDelegate(writer, "chunkIsHighlit");
        writeColorsDelegate(writer);
        writeComponentDelegate(writer, "getChunkHighlightSubtleTooltip", "getSubtleTooltip");
        writeComponentDelegate(writer, "getChunkHighlightBluntTooltip", "getBluntTooltip");
        writeWorldMapMinimapTooltipDelegate(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeConstructor(ClassWriter writer, String superInternal) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKESPECIAL, superInternal, "<init>", "(Z)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeBooleanDelegate(ClassWriter writer, String methodName) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName, "(" + RESOURCE_KEY_DESC + "II)Z", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, methodName, "(Ljava/lang/Object;II)Z", false);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeIntDelegate(ClassWriter writer, String methodName) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName, "(" + RESOURCE_KEY_DESC + "II)I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, methodName, "(Ljava/lang/Object;II)I", false);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeColorsDelegate(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PROTECTED, "getColors", "(" + RESOURCE_KEY_DESC + "II)[I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, "getColors", "(Ljava/lang/Object;II)[I", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeMinimapTooltipDelegate(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "addChunkHighlightTooltips",
                "(" + MINIMAP_COMPILER_DESC + RESOURCE_KEY_DESC + "III)V",
                null,
                null
        );
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitVarInsn(ILOAD, 4);
        method.visitVarInsn(ILOAD, 5);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, "addMinimapTooltip", "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeComponentDelegate(ClassWriter writer, String methodName, String bridgeName) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName, "(" + RESOURCE_KEY_DESC + "II)" + COMPONENT_DESC, null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, bridgeName, "(Ljava/lang/Object;II)" + COMPONENT_DESC, false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeWorldMapMinimapTooltipDelegate(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "addMinimapBlockHighlightTooltips",
                "(Ljava/util/List;" + RESOURCE_KEY_DESC + "III)V",
                null,
                null
        );
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ILOAD, 3);
        method.visitVarInsn(ILOAD, 4);
        method.visitVarInsn(ILOAD, 5);
        method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL, "addWorldMapMinimapTooltip", "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private SailboatXaeroRuntimeHighlighterFactory() {
    }
}
