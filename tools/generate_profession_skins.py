"""
使用基础皮肤生成所有职业的皮肤文件
"""
import shutil
from pathlib import Path

# 源皮肤
source_dir = Path("F:/Codex/sailboatmod/NPCskins")
male_skin = source_dir / "male_farmer.png"
female_skin = source_dir / "female_farmer.png"

# 目标目录
output_dir = Path("F:/Codex/sailboatmod/src/main/resources/assets/sailboatmod/textures/entity/resident")
output_dir.mkdir(parents=True, exist_ok=True)

# 职业列表
professions = [
    "farmer", "miner", "lumberjack", "fisherman",
    "blacksmith", "baker", "guard", "soldier", "builder"
]

# 为每个职业创建4个皮肤（2男2女）
for profession in professions:
    # 2个男性皮肤
    for i in range(1, 3):
        dest = output_dir / f"{profession}_male_{i}.png"
        shutil.copy2(male_skin, dest)
        print(f"Created: {dest.name}")

    # 2个女性皮肤
    for i in range(1, 3):
        dest = output_dir / f"{profession}_female_{i}.png"
        shutil.copy2(female_skin, dest)
        print(f"Created: {dest.name}")

print(f"\nTotal: {len(professions) * 4} skins created")
