"""
Download and organize Minecraft skins for resident professions.
"""
import requests
import os
from pathlib import Path

# Skin download URLs (to be filled with actual URLs from skin libraries)
SKIN_URLS = {
    "farmer": {
        "male": [
            # Add NameMC or other skin URLs here
        ],
        "female": [
            # Add NameMC or other skin URLs here
        ]
    },
    "miner": {"male": [], "female": []},
    "lumberjack": {"male": [], "female": []},
    "fisherman": {"male": [], "female": []},
    "blacksmith": {"male": [], "female": []},
    "baker": {"male": [], "female": []},
    "guard": {"male": [], "female": []},
    "soldier": {"male": [], "female": []},
    "builder": {"male": [], "female": []}
}

def download_skin(url, output_path):
    """Download a skin from URL to output path."""
    try:
        response = requests.get(url, timeout=10)
        response.raise_for_status()
        with open(output_path, 'wb') as f:
            f.write(response.content)
        print(f"Downloaded: {output_path}")
        return True
    except Exception as e:
        print(f"Failed to download {url}: {e}")
        return False

def main():
    output_dir = Path("F:/Codex/sailboatmod/src/main/resources/assets/sailboatmod/textures/entity/resident")
    output_dir.mkdir(parents=True, exist_ok=True)

    for profession, genders in SKIN_URLS.items():
        for gender, urls in genders.items():
            for i, url in enumerate(urls, 1):
                filename = f"{profession}_{gender}_{i}.png"
                output_path = output_dir / filename
                download_skin(url, output_path)

if __name__ == "__main__":
    main()
