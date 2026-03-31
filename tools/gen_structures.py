"""
Generate Minecraft NBT structure files for cottage variants and tavern.
Uses nbtlib to write valid .nbt structure format compatible with Forge 1.20.1.
"""
import nbtlib
from nbtlib import Compound, List, Int, Short, String, Byte
import gzip, io, struct

# Minecraft structure NBT format:
# - size: [x, y, z]
# - palette: list of block states
# - blocks: list of {pos:[x,y,z], state:palette_index, nbt:optional}
# - entities: []
# - DataVersion: 3465 (1.20.1)

DATA_VERSION = 3465

def block_state(name, props=None):
    tag = Compound({"Name": String(name)})
    if props:
        tag["Properties"] = Compound({k: String(v) for k, v in props.items()})
    return tag

def block_entry(x, y, z, state_idx):
    return Compound({
        "pos": List[Int]([Int(x), Int(y), Int(z)]),
        "state": Int(state_idx)
    })

def save_structure(path, size, palette, blocks):
    root = Compound({
        "size": List[Int]([Int(size[0]), Int(size[1]), Int(size[2])]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](blocks),
        "entities": List[Compound]([]),
        "DataVersion": Int(DATA_VERSION)
    })
    f = nbtlib.File(root, gzipped=True)
    f.save(path)
    print(f"  Saved: {path} ({len(blocks)} blocks)")

# ── Palette indices ──
AIR = 0
OAK_PLANKS = 1
OAK_LOG = 2
COBBLESTONE = 3
GLASS_PANE = 4
OAK_DOOR_LOWER = 5
OAK_DOOR_UPPER = 6
OAK_STAIRS_N = 7
OAK_STAIRS_S = 8
OAK_SLAB_TOP = 9
OAK_SLAB_BOT = 10
TORCH = 11
OAK_FENCE = 12
COTTAGE_CORE = 13
SPRUCE_PLANKS = 14
SPRUCE_LOG = 15
DARK_OAK_PLANKS = 16
BARREL = 17
CRAFTING_TABLE = 18
FURNACE = 19
BAR_CORE = 20
LANTERN = 21
CAMPFIRE = 22
BED_FOOT = 23
BED_HEAD = 24

COMMON_PALETTE = [
    block_state("minecraft:air"),                                    # 0
    block_state("minecraft:oak_planks"),                              # 1
    block_state("minecraft:oak_log", {"axis": "y"}),                 # 2
    block_state("minecraft:cobblestone"),                             # 3
    block_state("minecraft:glass_pane"),                              # 4
    block_state("minecraft:oak_door", {"half": "lower", "facing": "south", "open": "false", "hinge": "left"}), # 5
    block_state("minecraft:oak_door", {"half": "upper", "facing": "south", "open": "false", "hinge": "left"}), # 6
    block_state("minecraft:oak_stairs", {"facing": "north", "half": "bottom"}), # 7
    block_state("minecraft:oak_stairs", {"facing": "south", "half": "bottom"}), # 8
    block_state("minecraft:oak_slab", {"type": "top"}),              # 9
    block_state("minecraft:oak_slab", {"type": "bottom"}),           # 10
    block_state("minecraft:wall_torch", {"facing": "south"}),        # 11
    block_state("minecraft:oak_fence"),                               # 12
    block_state("sailboatmod:cottage"),                               # 13
    block_state("minecraft:spruce_planks"),                           # 14
    block_state("minecraft:spruce_log", {"axis": "y"}),              # 15
    block_state("minecraft:dark_oak_planks"),                         # 16
    block_state("minecraft:barrel", {"facing": "up", "open": "false"}), # 17
    block_state("minecraft:crafting_table"),                          # 18
    block_state("minecraft:furnace", {"facing": "south", "lit": "false"}), # 19
    block_state("sailboatmod:bar"),                                   # 20
    block_state("minecraft:lantern", {"hanging": "false"}),          # 21
    block_state("minecraft:campfire", {"facing": "south", "lit": "true", "signal_fire": "false"}), # 22
    block_state("minecraft:red_bed", {"facing": "south", "part": "foot", "occupied": "false"}), # 23
    block_state("minecraft:red_bed", {"facing": "south", "part": "head", "occupied": "false"}), # 24
]

def build_cottage(w, d, h, name, out_path):
    """Build a cottage: cobblestone base, oak plank walls, log pillars, slab roof."""
    blocks = []

    # Floor: cobblestone
    for x in range(w):
        for z in range(d):
            blocks.append(block_entry(x, 0, z, COBBLESTONE))

    # Walls: oak planks, corners are logs
    wall_h = h - 2  # leave room for roof
    for y in range(1, wall_h + 1):
        for x in range(w):
            for z in range(d):
                is_corner = (x in (0, w-1)) and (z in (0, d-1))
                is_wall = x == 0 or x == w-1 or z == 0 or z == d-1
                if is_corner:
                    blocks.append(block_entry(x, y, z, OAK_LOG))
                elif is_wall:
                    # Windows on y=2 for walls not at edges
                    if y == 2 and not (x in (0, w-1) or z in (0, d-1)):
                        if (x + z) % 3 == 0:
                            blocks.append(block_entry(x, y, z, GLASS_PANE))
                        else:
                            blocks.append(block_entry(x, y, z, OAK_PLANKS))
                    else:
                        blocks.append(block_entry(x, y, z, OAK_PLANKS))

    # Door: south wall center
    door_x = w // 2
    blocks.append(block_entry(door_x, 1, 0, OAK_DOOR_LOWER))
    blocks.append(block_entry(door_x, 2, 0, OAK_DOOR_UPPER))

    # Roof: slabs + stairs
    roof_y = wall_h + 1
    for x in range(w):
        for z in range(d):
            blocks.append(block_entry(x, roof_y, z, OAK_SLAB_BOT))

    # Core block at center
    cx, cz = w // 2, d // 2
    blocks.append(block_entry(cx, 1, cz, COTTAGE_CORE))

    # Interior: bed
    if w >= 5 and d >= 5:
        blocks.append(block_entry(1, 1, d-2, BED_FOOT))
        blocks.append(block_entry(1, 1, d-3, BED_HEAD))

    # Torch
    blocks.append(block_entry(cx, 2, 1, TORCH))

    save_structure(out_path, (w, h, d), COMMON_PALETTE, blocks)

def build_tavern(w, d, h, out_path):
    """Build a tavern: spruce/dark oak, bar counter, fireplace."""
    blocks = []

    # Floor: dark oak planks
    for x in range(w):
        for z in range(d):
            blocks.append(block_entry(x, 0, z, DARK_OAK_PLANKS))

    # Walls: spruce planks, corners are spruce logs
    wall_h = h - 2
    for y in range(1, wall_h + 1):
        for x in range(w):
            for z in range(d):
                is_corner = (x in (0, w-1)) and (z in (0, d-1))
                is_wall = x == 0 or x == w-1 or z == 0 or z == d-1
                if is_corner:
                    blocks.append(block_entry(x, y, z, SPRUCE_LOG))
                elif is_wall:
                    if y == 2 and not (x in (0, w-1) or z in (0, d-1)):
                        if (x + z) % 4 == 0:
                            blocks.append(block_entry(x, y, z, GLASS_PANE))
                        else:
                            blocks.append(block_entry(x, y, z, SPRUCE_PLANKS))
                    else:
                        blocks.append(block_entry(x, y, z, SPRUCE_PLANKS))

    # Door
    door_x = w // 2
    blocks.append(block_entry(door_x, 1, 0, OAK_DOOR_LOWER))
    blocks.append(block_entry(door_x, 2, 0, OAK_DOOR_UPPER))

    # Roof
    roof_y = wall_h + 1
    for x in range(w):
        for z in range(d):
            blocks.append(block_entry(x, roof_y, z, OAK_SLAB_BOT))

    # Bar counter: oak fence line
    for x in range(2, w - 2):
        blocks.append(block_entry(x, 1, d // 2, OAK_FENCE))
        blocks.append(block_entry(x, 1, d // 2 + 1, OAK_SLAB_BOT))

    # Bar core at center
    cx, cz = w // 2, d // 2
    blocks.append(block_entry(cx, 1, cz + 1, BAR_CORE))

    # Barrels behind counter
    blocks.append(block_entry(2, 1, d - 2, BARREL))
    blocks.append(block_entry(3, 1, d - 2, BARREL))
    blocks.append(block_entry(2, 2, d - 2, BARREL))

    # Fireplace
    blocks.append(block_entry(w - 2, 1, d - 2, CAMPFIRE))

    # Lanterns
    blocks.append(block_entry(cx, 1, 2, LANTERN))
    blocks.append(block_entry(cx, 1, d - 3, LANTERN))

    # Crafting table + furnace
    blocks.append(block_entry(1, 1, d - 2, CRAFTING_TABLE))
    blocks.append(block_entry(1, 1, d - 3, FURNACE))

    save_structure(out_path, (w, h, d), COMMON_PALETTE, blocks)

if __name__ == "__main__":
    import os
    out_dir = r"F:\Codex\sailboatmod\src\main\resources\data\sailboatmod\structures"
    os.makedirs(out_dir, exist_ok=True)

    print("Generating cottage variants...")
    build_cottage(7, 7, 6, "Small Cottage", os.path.join(out_dir, "cottage_small.nbt"))
    build_cottage(9, 9, 7, "Medium Cottage", os.path.join(out_dir, "cottage_medium.nbt"))
    build_cottage(11, 11, 8, "Large Cottage", os.path.join(out_dir, "cottage_large.nbt"))

    print("Generating tavern...")
    build_tavern(13, 11, 9, os.path.join(out_dir, "tavern.nbt"))

    print("Done!")
