"""
使用Mojang API下载玩家皮肤
"""
import requests
import base64
import json
from pathlib import Path

def get_skin_url_from_username(username):
    """从用户名获取皮肤URL"""
    try:
        # 获取UUID
        uuid_response = requests.get(f"https://api.mojang.com/users/profiles/minecraft/{username}")
        if uuid_response.status_code != 200:
            return None
        uuid = uuid_response.json()['id']

        # 获取皮肤信息
        profile_response = requests.get(f"https://sessionserver.mojang.com/session/minecraft/profile/{uuid}")
        if profile_response.status_code != 200:
            return None

        profile_data = profile_response.json()
        textures_encoded = profile_data['properties'][0]['value']
        textures_decoded = base64.b64decode(textures_encoded).decode('utf-8')
        textures_json = json.loads(textures_decoded)

        skin_url = textures_json['textures']['SKIN']['url']
        return skin_url
    except Exception as e:
        print(f"获取 {username} 皮肤失败: {e}")
        return None

def download_skin(url, output_path):
    """下载皮肤"""
    try:
        response = requests.get(url)
        response.raise_for_status()
        with open(output_path, 'wb') as f:
            f.write(response.content)
        return True
    except Exception as e:
        print(f"下载失败: {e}")
        return False

# 职业主题的玩家用户名（这些是示例，需要找真实存在的用户名）
PROFESSION_USERNAMES = {
    "farmer": ["Farmer", "FarmerJoe", "Farmhand", "Peasant"],
    "miner": ["Miner", "MinerSteve", "Digger", "Prospector"],
    "lumberjack": ["Lumberjack", "Woodcutter", "Logger", "Forester"],
    "fisherman": ["Fisherman", "Fisher", "Angler", "Sailor"],
    "blacksmith": ["Blacksmith", "Smith", "Forge", "Anvil"],
    "baker": ["Baker", "Chef", "Cook", "Pastry"],
    "guard": ["Guard", "Knight", "Sentinel", "Watchman"],
    "soldier": ["Soldier", "Warrior", "Fighter", "Trooper"],
    "builder": ["Builder", "Constructor", "Mason", "Architect"]
}

def main():
    output_dir = Path("F:/Codex/sailboatmod/src/main/resources/assets/sailboatmod/textures/entity/resident")
    output_dir.mkdir(parents=True, exist_ok=True)

    for profession, usernames in PROFESSION_USERNAMES.items():
        print(f"\n处理 {profession}...")
        count = 0
        for username in usernames:
            if count >= 4:  # 每个职业4个皮肤
                break

            skin_url = get_skin_url_from_username(username)
            if skin_url:
                gender = "male" if count < 2 else "female"  # 前2个male，后2个female
                filename = f"{profession}_{gender}_{count % 2 + 1}.png"
                output_path = output_dir / filename

                if download_skin(skin_url, output_path):
                    print(f"OK {filename}")
                    count += 1

if __name__ == "__main__":
    main()
