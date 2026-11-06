# Tile cache decoder regression

This harness runs the production `RoadPlannerTile.loadOrCreate` class from an explicit JAR and the Minecraft 1.21.1 `NativeImage` decoder. It does not start Minecraft or create an OpenGL context.

ASM replaces only the private `closeTextureOnly()` and `uploadTexture()` methods in an in-memory copy of the production tile class. The cache loader, placeholder detection, image allocation, and decoding are unchanged. The real image is closed directly by the harness after each case.

The cases cover:

- A valid small 256×256 PNG with every decoded pixel checked.
- A malformed PNG and a missing cache file, both producing the usual transparent placeholder.
- The actual 79,447-byte cached tile that can trigger the crash.
- A deterministic generated 256×256 PNG larger than 64 KiB, with every decoded pixel checked.
- A separate assertion that Minecraft's `NativeImage.read(byte[])` still reproduces the original LWJGL stack error, using the default 65,536-byte native stack.

Run from the repository with Java 21 and a local installed Minecraft 1.21.1 NeoForge runtime:

```powershell
python tools/verification/run_tile_cache_decode_regression.py `
  --mod-jar 'C:/path/to/sailboatmod.jar' `
  --large-png 'C:/path/to/actual-large-tile.png' `
  --client-root 'C:/path/to/.minecraft' `
  --version-json 'C:/path/to/version.json' `
  --jdk 'C:/Program Files/Java/jdk-21' `
  --output 'C:/path/to/test-output'
```

The runner uses the libraries named by the provided runtime version JSON, the runtime's mapped Minecraft client JAR, and Windows x64 LWJGL natives. No Gradle invocation, network access, cache deletion, production JAR change, or save edit occurs. A passing process exits zero and prints `PASS assertions=...`.

This verifies the actual cache-loading path without GL. It does not verify texture upload or the complete in-game Town UI.
