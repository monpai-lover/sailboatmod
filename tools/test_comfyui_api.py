#!/usr/bin/env python3
"""
使用 ComfyUI Python API 直接生成
"""
import sys
sys.path.append("F:/Codex/ComfyUI_windows_portable/ComfyUI")

import json
import random
from nodes import NODE_CLASS_MAPPINGS

# 加载节点
CheckpointLoader = NODE_CLASS_MAPPINGS["CheckpointLoaderSimple"]()
LoraLoader = NODE_CLASS_MAPPINGS["LoraLoader"]()
CLIPTextEncode = NODE_CLASS_MAPPINGS["CLIPTextEncode"]()
KSampler = NODE_CLASS_MAPPINGS["KSampler"]()
VAEDecode = NODE_CLASS_MAPPINGS["VAEDecode"]()
EmptyLatentImage = NODE_CLASS_MAPPINGS["EmptyLatentImage"]()
SaveImage = NODE_CLASS_MAPPINGS["SaveImage"]()

# 加载模型
print("加载 checkpoint...")
model, clip, vae = CheckpointLoader.load_checkpoint("flux-2-klein-base-9b-fp8.safetensors")

print("加载 LoRA...")
model, clip = LoraLoader.load_lora(model, clip, "MCSKIN_000005400.safetensors", 0.8, 0.8)

# 编码提示词
prompt = "Generate a 64x64 pixel texture maps of this Minecraft character, farmer, male, european style"
print(f"提示词: {prompt}")
cond = CLIPTextEncode.encode(clip, prompt)[0]
neg_cond = CLIPTextEncode.encode(clip, "blurry, low quality")[0]

# 创建潜在图像
latent = EmptyLatentImage.generate(512, 512, 1)[0]

# 采样
print("生成中...")
samples = KSampler.sample(
    model, random.randint(0, 2**31), 20, 7.0,
    "euler", "normal", cond, neg_cond, latent, 1.0
)[0]

# 解码
print("解码...")
image = VAEDecode.decode(vae, samples)[0]

# 保存
print("保存...")
SaveImage.save_images(image, "test_mcskin")
print("完成!")
