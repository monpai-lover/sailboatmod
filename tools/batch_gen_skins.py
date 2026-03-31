#!/usr/bin/env python3
import json
import requests
import argparse
import time
from pathlib import Path

PROFESSIONS = ["farmer", "miner", "lumberjack", "fisherman", "blacksmith", "baker", "guard", "unemployed"]
GENDERS = ["male", "female"]
CULTURES = ["european", "asian", "nordic", "desert", "tropical", "slavic"]
COMFYUI_URL = "http://127.0.0.1:8188"

def load_workflow(workflow_path):
    with open(workflow_path, 'r') as f:
        return json.load(f)

def queue_prompt(workflow, prompt_text, seed):
    workflow["3"]["inputs"]["text"] = prompt_text
    workflow["5"]["inputs"]["seed"] = seed
    payload = {"prompt": workflow}
    response = requests.post(f"{COMFYUI_URL}/prompt", json=payload)
    result = response.json()
    if "error" in result:
        print(f"Error: {result['error']}")
        return None
    return result.get("prompt_id", None)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=5)
    parser.add_argument("--output", type=str, default="../pixel/skins")
    args = parser.parse_args()

    workflow = load_workflow("comfyui_workflow_mcskin.json")
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    total = len(PROFESSIONS) * len(GENDERS) * len(CULTURES) * args.count
    count = 0

    for prof in PROFESSIONS:
        for gender in GENDERS:
            for culture in CULTURES:
                for i in range(args.count):
                    prompt = f"minecraft skin, {prof}, {gender}, {culture} style, 64x64 pixel art, front view, T-pose"
                    seed = hash(f"{prof}_{gender}_{culture}_{i}") % (2**31)

                    print(f"[{count+1}/{total}] Generating {prof}_{gender}_{culture}_{i}...")
                    queue_prompt(workflow, prompt, seed)
                    time.sleep(2)
                    count += 1

    print(f"Queued {total} images. Check ComfyUI output folder.")

if __name__ == "__main__":
    main()
