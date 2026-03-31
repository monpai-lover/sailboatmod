#!/usr/bin/env python3
import torch
from diffusers import FluxPipeline

MODEL_PATH = "F:/Codex/ComfyUI_windows_portable/ComfyUI/models/checkpoints/flux-2-klein-base-9b-fp8.safetensors"
LORA_PATH = "F:/Codex/ComfyUI_windows_portable/ComfyUI/models/loras/MCSKIN_000005400.safetensors"

# 加载模型
pipe = FluxPipeline.from_single_file(
    MODEL_PATH,
    torch_dtype=torch.float16
)
pipe.load_lora_weights(LORA_PATH)
pipe.to("cuda")

# 生成测试
prompt = "Generate a 64x64 pixel texture maps of this Minecraft character, farmer, male, european style"
image = pipe(prompt, num_inference_steps=20, guidance_scale=7.0).images[0]
image.save("test_skin.png")
print("生成完成: test_skin.png")
