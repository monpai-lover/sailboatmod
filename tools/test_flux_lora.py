#!/usr/bin/env python3
"""
直接使用本地 FLUX 模型和 MCSkin LoRA 生成皮肤
需要先安装: pip install diffusers transformers accelerate safetensors
"""
import torch
from diffusers import DiffusionPipeline
from pathlib import Path

# 本地模型路径
BASE_MODEL = "black-forest-labs/FLUX.1-dev"  # 使用在线模型作为基础
LORA_PATH = "F:/Codex/ComfyUI_windows_portable/ComfyUI/models/loras/MCSKIN_000005400.safetensors"

print("加载 FLUX 模型...")
pipe = DiffusionPipeline.from_pretrained(
    BASE_MODEL,
    torch_dtype=torch.bfloat16
)
pipe.to("cuda")

print("加载 MCSkin LoRA...")
pipe.load_lora_weights(LORA_PATH)

# 测试生成
prompt = "Generate a 64x64 pixel texture maps of this Minecraft character, farmer, male, european style"
print(f"生成提示词: {prompt}")

image = pipe(
    prompt,
    num_inference_steps=20,
    guidance_scale=3.5,
    height=1024,
    width=1024
).images[0]

output_path = "test_mcskin.png"
image.save(output_path)
print(f"生成完成: {output_path}")
