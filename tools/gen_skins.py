"""
Batch generate Minecraft NPC skins using FLUX.2-klein-9B + MCSkin LoRA.
Outputs 64x64 PNG skin textures to the mod's skin cache directory.

Usage:
    python tools/gen_skins.py --count 50 --output F:/Codex/sailboatmod/src/main/resources/assets/sailboatmod/textures/entity/resident/skins/

Requires: torch, diffusers, transformers, accelerate, safetensors, Pillow
"""
import argparse
import hashlib
import os
import random

# Profession-themed prompts for variety
PROFESSION_PROMPTS = {
    "farmer": [
        "a farmer villager wearing a straw hat and brown overalls with a green shirt",
        "a peasant farmer with a tan tunic and muddy boots, sun-weathered skin",
        "a female farmer with braided hair, apron over a cream dress, holding wheat",
    ],
    "miner": [
        "a miner with a leather helmet and gray dusty clothes, pickaxe on back",
        "a dwarf-like miner with a thick beard, iron helmet, dark gray outfit",
        "a female miner with goggles on forehead, dark overalls and lantern",
    ],
    "lumberjack": [
        "a lumberjack with a red plaid flannel shirt and suspenders, thick beard",
        "a woodcutter with a green tunic and leather vest, axe holster",
        "a female lumberjack with short hair, rolled sleeves, brown work pants",
    ],
    "blacksmith": [
        "a blacksmith with a leather apron, muscular arms, soot-covered face",
        "an armorer with a heavy leather vest, metal gauntlets, bald head",
        "a female blacksmith with tied-back hair, forge apron, tongs in belt",
    ],
    "baker": [
        "a baker with a white chef hat and flour-dusted apron over brown clothes",
        "a pastry chef with a puffy white hat, cheerful round face, white outfit",
        "a female baker with a bonnet, rosy cheeks, cream-colored dress and apron",
    ],
    "fisherman": [
        "a fisherman with a wide-brim hat, blue vest, rolled-up pants and boots",
        "a sailor fisherman with a striped shirt, weathered face, rope belt",
        "a female fisher with a bandana, tanned skin, simple blue dress",
    ],
    "guard": [
        "a town guard with chainmail armor, iron helmet, blue tabard with crest",
        "a watchman with a leather cuirass, spear, and a stern expression",
        "a female guard with plate shoulder pads, sword at hip, short hair",
    ],
    "soldier": [
        "a medieval soldier in full iron armor with a red cape and longsword",
        "a pikeman soldier with a kettle helmet, padded armor, and pike",
        "a female knight with polished armor, shield with lion emblem, ponytail",
    ],
}

TRIGGER = "Generate a 64x64 pixel texture maps of this Minecraft character"
LORA_PATH = r"F:\Codex\NPCSkinGen\MCSKIN_000005400.safetensors"


def generate_skins(count, output_dir, device="cuda"):
    os.makedirs(output_dir, exist_ok=True)

    print(f"Loading FLUX pipeline on {device}...")
    from diffusers import FluxPipeline
    import torch

    pipe = FluxPipeline.from_pretrained(
        "black-forest-labs/FLUX.2-klein-9B",
        torch_dtype=torch.bfloat16,
    )
    pipe.load_lora_weights(LORA_PATH)
    pipe = pipe.to(device)
    pipe.enable_attention_slicing()

    professions = list(PROFESSION_PROMPTS.keys())
    generated = 0

    for i in range(count):
        prof = professions[i % len(professions)]
        prompt_variants = PROFESSION_PROMPTS[prof]
        desc = random.choice(prompt_variants)
        full_prompt = f"{TRIGGER}, {desc}"

        print(f"[{i+1}/{count}] Generating {prof} skin...")
        try:
            result = pipe(
                prompt=full_prompt,
                height=1024,
                width=1024,
                num_inference_steps=20,
                guidance_scale=3.5,
            )
            image = result.images[0]

            # Downscale to 64x64 using nearest neighbor (pixel art)
            skin = image.resize((64, 64), resample=0)  # NEAREST

            # Save with hash-based name
            skin_bytes = skin.tobytes()
            skin_hash = hashlib.sha256(skin_bytes).hexdigest()[:12]
            filename = f"{prof}_{skin_hash}.png"
            filepath = os.path.join(output_dir, filename)
            skin.save(filepath)
            print(f"  Saved: {filename}")
            generated += 1
        except Exception as e:
            print(f"  Error: {e}")
            continue

    print(f"\nDone! Generated {generated}/{count} skins in {output_dir}")


def generate_skins_cpu_fallback(count, output_dir):
    """Fallback: generate simple procedural skins without AI model."""
    from PIL import Image, ImageDraw
    os.makedirs(output_dir, exist_ok=True)

    SKIN_COLORS = [(210,170,130), (180,140,100), (140,100,70), (230,200,170), (160,120,80)]
    PROF_COLORS = {
        "farmer":     [(139,119,42), (107,85,47)],
        "miner":      [(100,100,110), (70,70,80)],
        "lumberjack": [(180,60,40), (100,70,50)],
        "blacksmith": [(80,60,50), (50,40,35)],
        "baker":      [(240,230,220), (200,190,170)],
        "fisherman":  [(60,100,140), (40,70,100)],
        "guard":      [(60,80,140), (140,140,150)],
        "soldier":    [(160,50,50), (140,140,150)],
    }

    professions = list(PROF_COLORS.keys())
    generated = 0

    for i in range(count):
        prof = professions[i % len(professions)]
        skin_color = random.choice(SKIN_COLORS)
        outfit_main, outfit_accent = PROF_COLORS[prof]

        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

        # Head (8,0)-(16,8) front face area in standard skin layout
        for x in range(8, 16):
            for y in range(8, 16):
                img.putpixel((x, y), (*skin_color, 255))
        # Hair
        hair_color = (random.randint(20,80), random.randint(15,60), random.randint(10,40))
        for x in range(8, 16):
            img.putpixel((x, 8), (*hair_color, 255))
            img.putpixel((x, 9), (*hair_color, 255))
        # Eyes
        img.putpixel((9, 12), (40, 40, 40, 255))
        img.putpixel((12, 12), (40, 40, 40, 255))
        # Mouth
        img.putpixel((10, 14), (180, 100, 100, 255))
        img.putpixel((11, 14), (180, 100, 100, 255))

        # Body front (20,20)-(28,32)
        for x in range(20, 28):
            for y in range(20, 32):
                c = outfit_main if (y < 26) else outfit_accent
                img.putpixel((x, y), (*c, 255))

        # Arms (44,20)-(48,32) right arm, (36,52)-(40,64) left arm
        for x in range(44, 48):
            for y in range(20, 32):
                img.putpixel((x, y), (*skin_color, 255))
        for x in range(36, 40):
            for y in range(52, 64):
                img.putpixel((x, y), (*skin_color, 255))

        # Legs (4,20)-(8,32) right, (20,52)-(24,64) left
        for x in range(4, 8):
            for y in range(20, 32):
                img.putpixel((x, y), (*outfit_accent, 255))
        for x in range(20, 24):
            for y in range(52, 64):
                img.putpixel((x, y), (*outfit_accent, 255))

        # Head top (8,0)-(16,8)
        for x in range(8, 16):
            for y in range(0, 8):
                img.putpixel((x, y), (*hair_color, 255))

        skin_hash = hashlib.sha256(img.tobytes()).hexdigest()[:12]
        filename = f"{prof}_{skin_hash}.png"
        img.save(os.path.join(output_dir, filename))
        generated += 1
        if (i + 1) % 10 == 0:
            print(f"  [{i+1}/{count}] generated...")

    print(f"Done! Generated {generated} procedural skins in {output_dir}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate Minecraft NPC skins")
    parser.add_argument("--count", type=int, default=50, help="Number of skins to generate")
    parser.add_argument("--output", type=str,
                        default=r"F:\Codex\sailboatmod\src\main\resources\assets\sailboatmod\textures\entity\resident\skins",
                        help="Output directory")
    parser.add_argument("--mode", choices=["ai", "procedural"], default="ai",
                        help="ai=FLUX model, procedural=simple colored skins")
    parser.add_argument("--device", type=str, default="cuda", help="cuda or cpu")
    args = parser.parse_args()

    if args.mode == "ai":
        try:
            import torch
            if not torch.cuda.is_available() and args.device == "cuda":
                print("CUDA not available, falling back to procedural mode")
                generate_skins_cpu_fallback(args.count, args.output)
            else:
                generate_skins(args.count, args.output, args.device)
        except ImportError:
            print("torch not installed, falling back to procedural mode")
            generate_skins_cpu_fallback(args.count, args.output)
    else:
        generate_skins_cpu_fallback(args.count, args.output)
