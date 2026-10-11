"""Package a reproducible source-only Claude market-web handoff using stdlib.

Run from any directory: python tools/package_web_market_handoff.py [--list]
Only the explicit allowlist is copied. No Git history, worlds, logs or secrets
are discovered by traversing the repository root.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_ROOT = "sailboatmod"
ARCHIVE_NAME = "sailboatmod-web-market-handoff-1.21.1-neoforge.zip"
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
REQUIRED_FILES = (
    "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "AGENTS.md", "CLAUDE.md", "README.md", "README.txt", "LICENSE.txt", "NOTICE", "CREDITS.txt",
    ".gitattributes", ".gitignore",
    "docs/handoff/CLAUDE_WEB_MARKET_HANDOFF.md",
    "tools/check_marketweb_frontend.js", "tools/marketweb_mock_server.js",
    "tools/package_web_market_handoff.py",
)
REQUIRED_TREES = (
    "src/main/java", "src/main/resources", "src/marketweb/resources", "src/test/java", "src/test/resources",
)
OPTIONAL_TREES = ("src/generated/resources", "docs/handoff")
OPTIONAL_DOCS = (
    "docs/superpowers/plans/2026-10-11-neoforge-migration.md",
    "docs/superpowers/plans/2026-06-13-market-wallet-and-map-repair.md",
    "docs/superpowers/plans/2026-06-13-market-web-map.md",
    "docs/superpowers/plans/2026-06-13-market-web-map-logistics.md",
    "docs/superpowers/plans/2026-06-14-market-pricing-redesign.md",
    "docs/superpowers/plans/2026-06-14-squaremap-market-web-map.md",
    "docs/superpowers/plans/2026-06-14-squaremap-parity-market-web-map.md",
    "docs/superpowers/plans/2026-06-14-webmap-logistics-trace.md",
    "docs/superpowers/specs/2026-06-13-market-web-map-design.md",
    "docs/superpowers/specs/2026-06-13-market-web-server-tile-render-design.md",
    "docs/superpowers/specs/2026-06-14-squaremap-parity-market-web-map-design.md",
    "docs/superpowers/specs/2026-06-14-webmap-logistics-trace-design.md",
    "docs/superpowers/specs/2026-06-14-transport-dispatch-redesign-design.md",
    "docs/superpowers/specs/2026-06-14-p2p-freight-design.md",
    "docs/superpowers/specs/2026-06-15-p2p-freight-phase5-ui-design.md",
)
FORBIDDEN_PARTS = frozenset({
    ".git", ".gradle", ".claude", ".superpowers", ".worktrees", "__pycache__",
    "node_modules", "world", "worlds", "saves", "logs", "NPCskins",
    "marketweb-minecraft-assets", "run", "run-data", "run-neoforge",
})
FORBIDDEN_SUFFIXES = frozenset({".db", ".sqlite", ".sqlite3", ".log", ".pem", ".key", ".p12", ".pfx"})


def within(path: Path, parent: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(parent.resolve()):
        raise RuntimeError(f"Path escapes intended directory: {path}")
    return resolved


def allowed(relative: Path) -> bool:
    if any(part in FORBIDDEN_PARTS for part in relative.parts):
        return False
    if relative.name == ".env" or relative.name.startswith(".env."):
        return False
    if relative.suffix.lower() in FORBIDDEN_SUFFIXES:
        return False
    return True


def source_inventory() -> tuple[list[Path], list[str]]:
    selected: set[Path] = set()
    omitted: list[str] = []

    def add(path: Path) -> None:
        # Never follow symlinks or junctions outside the selected source tree.
        within(path, ROOT)
        relative = path.relative_to(ROOT)
        if path.is_symlink():
            raise RuntimeError(f"Symlink cannot enter handoff: {relative.as_posix()}")
        if allowed(relative):
            selected.add(relative)
        else:
            omitted.append(relative.as_posix())

    for relative in REQUIRED_FILES:
        path = ROOT / relative
        if not path.is_file():
            raise RuntimeError(f"Required handoff file missing: {relative}")
        add(path)
    for relative in OPTIONAL_DOCS:
        path = ROOT / relative
        if path.is_file():
            add(path)
    for relative in REQUIRED_TREES + OPTIONAL_TREES:
        tree = ROOT / relative
        if not tree.is_dir():
            if relative in REQUIRED_TREES:
                raise RuntimeError(f"Required handoff tree missing: {relative}")
            continue
        within(tree, ROOT)
        for directory, directories, files in os.walk(tree, followlinks=False):
            directories[:] = sorted(name for name in directories if name not in FORBIDDEN_PARTS)
            for name in files:
                add(Path(directory) / name)
    return sorted(selected, key=lambda path: path.as_posix()), sorted(set(omitted))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_metadata() -> dict[str, object]:
    def git(*arguments: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(ROOT), *arguments], capture_output=True, text=True, check=False,
        )
        return result.stdout.strip() if result.returncode == 0 else "unavailable"

    try:
        return {
            "branch": git("branch", "--show-current"),
            "baseCommit": git("rev-parse", "HEAD"),
            "workingTreeSnapshot": True,
            "gitHistoryIncluded": False,
        }
    except FileNotFoundError:
        return {"branch": "unavailable", "baseCommit": "unavailable", "workingTreeSnapshot": True, "gitHistoryIncluded": False}


def verify_archive(archive: Path, stage: Path, records: list[dict[str, object]]) -> None:
    expected = {f"{ARCHIVE_ROOT}/{record['path']}" for record in records}
    expected.add(f"{ARCHIVE_ROOT}/HANDOFF_MANIFEST.json")
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if len(names) != len(expected) or set(names) != expected:
            raise RuntimeError("ZIP inventory differs from selected source files")
        bad_crc = bundle.testzip()
        if bad_crc is not None:
            raise RuntimeError(f"ZIP CRC failed: {bad_crc}")
        for record in records:
            name = f"{ARCHIVE_ROOT}/{record['path']}"
            payload = bundle.read(name)
            if len(payload) != record["bytes"] or sha256(payload) != record["sha256"]:
                raise RuntimeError(f"ZIP content mismatch: {name}")
        if bundle.read(f"{ARCHIVE_ROOT}/HANDOFF_MANIFEST.json") != (stage / "HANDOFF_MANIFEST.json").read_bytes():
            raise RuntimeError("ZIP manifest mismatch")


def package() -> Path:
    inventory, omitted = source_inventory()
    handoff_build = within(ROOT / "build" / "handoff", ROOT)
    artifact_root = within(ROOT / "artifacts", ROOT)
    handoff_build.mkdir(parents=True, exist_ok=True)
    artifact_root.mkdir(parents=True, exist_ok=True)
    archive = within(artifact_root / ARCHIVE_NAME, artifact_root)
    # The new temporary directory is validated before TemporaryDirectory cleanup.
    with tempfile.TemporaryDirectory(prefix="web-market-", dir=handoff_build) as temporary:
        temp_root = within(Path(temporary), handoff_build)
        stage = within(temp_root / ARCHIVE_ROOT, temp_root)
        stage.mkdir()
        records: list[dict[str, object]] = []
        for relative in inventory:
            source = within(ROOT / relative, ROOT)
            payload = source.read_bytes()
            target = within(stage / relative, stage)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            records.append({"path": relative.as_posix(), "bytes": len(payload), "sha256": sha256(payload)})
        manifest = {
            "formatVersion": 1,
            "purpose": "Claude Minecraft 1.21.1 NeoForge market-web refactor source handoff",
            "source": git_metadata(),
            "allowlist": {"files": REQUIRED_FILES, "trees": REQUIRED_TREES, "optionalTrees": OPTIONAL_TREES, "optionalDocs": OPTIONAL_DOCS},
            "excludedDiscoveredFiles": omitted,
            "excludedByDesign": ["Git history", "Gradle/build caches", "runtime/world data", "databases", "logs", "environment/secrets", "root NPCskins and unrelated work assets"],
            "verification": "ZIP inventory, per-file SHA-256 and CRC; not a claim of passing game build/tests",
            "files": records,
        }
        (stage / "HANDOFF_MANIFEST.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n",
        )
        temporary_archive = temp_root / ARCHIVE_NAME
        staged_paths = sorted(stage.rglob("*"), key=lambda path: path.relative_to(stage).as_posix())
        with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
            for path in staged_paths:
                if not path.is_file():
                    continue
                name = f"{ARCHIVE_ROOT}/{path.relative_to(stage).as_posix()}"
                entry = zipfile.ZipInfo(name, date_time=FIXED_ZIP_TIME)
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.create_system = 3
                entry.external_attr = (0o100755 if path.name == "gradlew" else 0o100644) << 16
                bundle.writestr(entry, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        verify_archive(temporary_archive, stage, records)
        # Fail closed if source content or inventory changed while the snapshot was staged.
        current_inventory, current_omitted = source_inventory()
        if current_inventory != inventory or current_omitted != omitted:
            raise RuntimeError("Source inventory changed during packaging; wait for a stable snapshot and retry")
        for record in records:
            payload = (ROOT / str(record["path"])).read_bytes()
            if len(payload) != record["bytes"] or sha256(payload) != record["sha256"]:
                raise RuntimeError(f"Source changed during packaging: {record['path']}")
        checksum = sha256(temporary_archive.read_bytes())
        temporary_archive.replace(archive)
        checksum_file = within(archive.with_suffix(archive.suffix + ".sha256"), artifact_root)
        checksum_file.write_text(f"{checksum}  {archive.name}\n", encoding="ascii", newline="\n")
        print(f"Verified {len(records)} source files plus manifest; ZIP inventory, SHA-256 and CRC pass.")
        print(f"Archive: {archive}")
        print(f"Bytes: {archive.stat().st_size}")
        print(f"SHA-256: {checksum}")
    return archive


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="Print selected source inventory without staging or creating a ZIP")
    arguments = parser.parse_args()
    if arguments.list:
        inventory, omitted = source_inventory()
        size = sum((ROOT / relative).stat().st_size for relative in inventory)
        for relative in inventory:
            print(relative.as_posix())
        print(f"Selected files: {len(inventory)}; uncompressed bytes: {size}; omitted restricted files: {len(omitted)}")
        return
    package()


if __name__ == "__main__":
    main()
