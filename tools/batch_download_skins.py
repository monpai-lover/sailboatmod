"""
批量下载Minecraft皮肤
使用方法：在skin_urls.txt中每行填写一个皮肤URL，格式：
profession,gender,url
例如：farmer,male,https://...
"""
import requests
from pathlib import Path

def download_skins_from_file(input_file="skin_urls.txt"):
    output_dir = Path("F:/Codex/sailboatmod/src/main/resources/assets/sailboatmod/textures/entity/resident")
    output_dir.mkdir(parents=True, exist_ok=True)

    counters = {}

    with open(input_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue

            parts = line.split(',')
            if len(parts) != 3:
                print(f"跳过无效行: {line}")
                continue

            profession, gender, url = parts
            profession = profession.strip()
            gender = gender.strip()
            url = url.strip()

            key = f"{profession}_{gender}"
            counters[key] = counters.get(key, 0) + 1

            filename = f"{profession}_{gender}_{counters[key]}.png"
            output_path = output_dir / filename

            try:
                response = requests.get(url, timeout=10)
                response.raise_for_status()
                with open(output_path, 'wb') as out:
                    out.write(response.content)
                print(f"✓ {filename}")
            except Exception as e:
                print(f"✗ {filename}: {e}")

if __name__ == "__main__":
    download_skins_from_file()
