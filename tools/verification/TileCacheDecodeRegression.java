import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryStack;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Runs the production cache loader and Minecraft PNG decoder without starting a game.
 * Only the two private GL-facing lifecycle methods are replaced in the in-memory
 * test class. The loadOrCreate bytecode and all decoding/placeholder logic remain real.
 * Run with: TileCacheDecodeRegression <production-mod.jar> <actual-large.png> <scratch-dir>
 */
public final class TileCacheDecodeRegression {
    private static final String TARGET = "com.monpai.sailboatmod.client.roadplanner.RoadPlannerTile";
    private static final String TARGET_PATH = TARGET.replace('.', '/') + ".class";
    private static int assertions;

    private static final class IsolatedTileLoader extends ClassLoader {
        private final byte[] isolated;
        IsolatedTileLoader(Path jar) throws Exception {
            super(TileCacheDecodeRegression.class.getClassLoader());
            byte[] original;
            try (JarFile file = new JarFile(jar.toFile())) {
                original = file.getInputStream(file.getJarEntry(TARGET_PATH)).readAllBytes();
            }
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(0);
            int[] changed = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if ((name.equals("closeTextureOnly") || name.equals("uploadTexture"))
                            && descriptor.equals("()V")) {
                        if ((access & Opcodes.ACC_PRIVATE) == 0) throw new AssertionError("Expected private GL method");
                        changed[0]++;
                        mv.visitCode();
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(0, 1);
                        mv.visitEnd();
                        return null;
                    }
                    return mv;
                }
            }, 0);
            if (changed[0] != 2) throw new AssertionError("Exactly two GL methods must be isolated");
            isolated = writer.toByteArray();
        }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = name.equals(TARGET) ? defineClass(name, isolated, 0, isolated.length) : super.loadClass(name, false);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static Path writeFixture(Path root, String name, boolean noise) throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0x5A17L);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            int color = noise ? random.nextInt() : y * 0x00010101;
            image.setRGB(x, y, 0xFF000000 | (color & 0xFFFFFF));
        }
        Path path = root.resolve(name);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static Object newTile(Class<?> tileClass) throws Exception {
        return tileClass.getDeclaredConstructors()[0].newInstance(new Object[] {null});
    }

    private static void load(Class<?> tileClass, Object tile, Path path) throws Throwable {
        try {
            tileClass.getDeclaredMethod("loadOrCreate", File.class).invoke(tile, path.toFile());
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static NativeImage pixels(Class<?> tileClass, Object tile) throws Exception {
        Field field = tileClass.getDeclaredField("image");
        field.setAccessible(true);
        return (NativeImage) field.get(tile);
    }

    private static void validImage(Class<?> tileClass, Path path) throws Throwable {
        Object tile = newTile(tileClass);
        NativeImage expected;
        try (var stream = Files.newInputStream(path)) { expected = NativeImage.read(stream); }
        try (expected) {
            load(tileClass, tile, path);
            try (NativeImage image = pixels(tileClass, tile)) {
                require(image != null, "Decoded image must be retained");
                require(image.getWidth() == 256 && image.getHeight() == 256, "Cache dimensions must remain 256x256");
                require((boolean) tileClass.getDeclaredMethod("loadedFromCache").invoke(tile), "Valid cache must be marked loaded");
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
                    if (image.getPixelRGBA(x, y) != expected.getPixelRGBA(x, y)) throw new AssertionError("Decoded cache pixel mismatch");
                }
                assertions++;
            }
        }
    }

    private static void placeholder(Class<?> tileClass, Path path) throws Throwable {
        Object tile = newTile(tileClass);
        load(tileClass, tile, path);
        try (NativeImage image = pixels(tileClass, tile)) {
            require(image != null, "Missing/malformed cache must create an image");
            require(image.getWidth() == 256 && image.getHeight() == 256, "Placeholder dimensions");
            require(!(boolean) tileClass.getDeclaredMethod("loadedFromCache").invoke(tile), "Fallback must not mark cache loaded");
            require(image.getPixelRGBA(8, 8) == 0, "Placeholder remains transparent");
        }
    }

    public static void main(String[] args) throws Throwable {
        Path jar = Path.of(args[0]);
        Path actual = Path.of(args[1]);
        Path scratch = Path.of(args[2]);
        Files.createDirectories(scratch);
        Path noise = writeFixture(scratch, "noise256.png", true);
        Path small = writeFixture(scratch, "small256.png", false);
        Path malformed = scratch.resolve("malformed.png");
        Files.write(malformed, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        require(Files.size(actual) > 65536 && Files.size(noise) > 65536, "Both large regression PNGs exceed 64KiB");
        require(Files.size(small) < 65536, "Ordinary small PNG stays below 64KiB");
        System.out.println("LWJGL stack=" + MemoryStack.stackGet().getSize());
        require(MemoryStack.stackGet().getSize() == 65536, "Use default LWJGL stack for the regression");
        System.out.println("actualPNG=" + Files.size(actual) + ", generatedPNG=" + Files.size(noise));
        boolean legacyFailed = false;
        try (NativeImage ignored = NativeImage.read(Files.readAllBytes(actual))) {
            throw new AssertionError("Minecraft byte[] decoder should reproduce the known defect");
        } catch (OutOfMemoryError expected) {
            require(expected.getMessage().contains("Out of stack space"), "Exact legacy failure");
            legacyFailed = true;
        }
        require(legacyFailed, "Legacy overload reproduction");
        Class<?> tile = new IsolatedTileLoader(jar).loadClass(TARGET);
        validImage(tile, small);
        System.out.println("PASS small valid cache (all pixels)");
        placeholder(tile, malformed);
        System.out.println("PASS malformed PNG fallback");
        placeholder(tile, scratch.resolve("missing.png"));
        System.out.println("PASS missing cache fallback");
        validImage(tile, actual);
        System.out.println("PASS actual >64KiB cache (all pixels)");
        validImage(tile, noise);
        System.out.println("PASS generated >64KiB cache (all pixels)");
        System.out.println("PASS assertions=" + assertions + ", isolatedGLMethods=2, productionDecoder=unchanged");
    }
}
