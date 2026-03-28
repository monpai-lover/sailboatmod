#!/usr/bin/env python3
"""Small image generation/edit CLI for OpenAI-compatible relay providers.

Examples:
  py tools/imagegen_relay.py generate ^
    --base-url https://example.com/v1 ^
    --api-key-env MYRELAY_API_KEY ^
    --model gpt-image-1 ^
    --prompt "pixel art dock block texture" ^
    --out output/imagegen/dock.png
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from pathlib import Path
from typing import Iterable, List, Optional


DEFAULT_MODEL = "gpt-image-1"
DEFAULT_SIZE = "1024x1024"
DEFAULT_QUALITY = "auto"
DEFAULT_OUTPUT_FORMAT = "png"


def die(message: str, code: int = 1) -> "None":
    print(f"Error: {message}", file=sys.stderr)
    raise SystemExit(code)


def warn(message: str) -> None:
    print(f"Warning: {message}", file=sys.stderr)


def normalize_output_format(value: Optional[str]) -> str:
    fmt = (value or DEFAULT_OUTPUT_FORMAT).lower()
    if fmt == "jpg":
        fmt = "jpeg"
    if fmt not in {"png", "jpeg", "webp"}:
        die("output format must be png, jpeg/jpg, or webp")
    return fmt


def read_prompt(prompt: Optional[str], prompt_file: Optional[str]) -> str:
    if prompt and prompt_file:
        die("use --prompt or --prompt-file, not both")
    if prompt_file:
        path = Path(prompt_file)
        if not path.exists():
            die(f"prompt file not found: {path}")
        return path.read_text(encoding="utf-8").strip()
    if prompt:
        return prompt.strip()
    die("missing prompt")


def resolve_api_key(env_name: str) -> str:
    value = os.getenv(env_name)
    if value:
        return value
    if env_name != "OPENAI_API_KEY" and os.getenv("OPENAI_API_KEY"):
        return os.getenv("OPENAI_API_KEY", "")
    if env_name != "MYRELAY_API_KEY" and os.getenv("MYRELAY_API_KEY"):
        return os.getenv("MYRELAY_API_KEY", "")
    die(f"environment variable {env_name} is not set")


def resolve_base_url(cli_value: Optional[str]) -> Optional[str]:
    if cli_value:
        return cli_value.rstrip("/")
    env_value = os.getenv("OPENAI_BASE_URL")
    if env_value:
        return env_value.rstrip("/")
    return None


def create_client(api_key: str, base_url: Optional[str]):
    try:
        from openai import OpenAI
    except ImportError:
        die("python package 'openai' is not installed. Run: py -3.14 -m pip install --user openai")
    kwargs = {"api_key": api_key}
    if base_url:
        kwargs["base_url"] = base_url
    return OpenAI(**kwargs)


def build_output_paths(out: str, output_format: str, count: int) -> List[Path]:
    base = Path(out)
    if base.suffix == "":
        base = base.with_suffix("." + output_format)
    elif base.suffix.lower().lstrip(".") != output_format:
        warn(f"output extension {base.suffix} does not match output format {output_format}")
    if count == 1:
        return [base]
    return [base.with_name(f"{base.stem}-{idx}{base.suffix}") for idx in range(1, count + 1)]


def ensure_writable(paths: Iterable[Path], force: bool) -> None:
    for path in paths:
        if path.exists() and not force:
            die(f"output already exists: {path} (use --force to overwrite)")
        path.parent.mkdir(parents=True, exist_ok=True)


def decode_item(item) -> bytes:
    b64_value = getattr(item, "b64_json", None)
    if b64_value:
        return base64.b64decode(b64_value)
    url = getattr(item, "url", None)
    if url:
        die("provider returned image URLs instead of base64 data; this script currently expects base64 output")
    die("provider returned no image content")


def write_results(result, output_paths: List[Path]) -> None:
    if not getattr(result, "data", None):
        die("provider returned empty image result")
    for index, item in enumerate(result.data):
        if index >= len(output_paths):
            break
        path = output_paths[index]
        path.write_bytes(decode_item(item))
        print(f"Wrote {path}")


def print_request(endpoint: str, payload: dict, output_paths: List[Path], base_url: Optional[str], api_key_env: str) -> None:
    preview = {
        "endpoint": endpoint,
        "base_url": base_url,
        "api_key_env": api_key_env,
        "outputs": [str(path) for path in output_paths],
        **payload,
    }
    print(json.dumps(preview, indent=2, ensure_ascii=False))


def run_generate(args: argparse.Namespace) -> None:
    prompt = read_prompt(args.prompt, args.prompt_file)
    api_key = resolve_api_key(args.api_key_env)
    base_url = resolve_base_url(args.base_url)
    output_format = normalize_output_format(args.output_format)
    payload = {
        "model": args.model,
        "prompt": prompt,
        "size": args.size,
        "quality": args.quality,
        "n": args.n,
        "output_format": output_format,
    }
    if args.background:
        payload["background"] = args.background
    if args.output_compression is not None:
        payload["output_compression"] = args.output_compression
    output_paths = build_output_paths(args.out, output_format, args.n)
    ensure_writable(output_paths, args.force)

    if args.dry_run:
        print_request("/images/generations", payload, output_paths, base_url, args.api_key_env)
        return

    client = create_client(api_key, base_url)
    print(f"Calling {base_url or 'default OpenAI endpoint'} /images/generations", file=sys.stderr)
    result = client.images.generate(**payload)
    write_results(result, output_paths)


def run_edit(args: argparse.Namespace) -> None:
    prompt = read_prompt(args.prompt, args.prompt_file)
    api_key = resolve_api_key(args.api_key_env)
    base_url = resolve_base_url(args.base_url)
    output_format = normalize_output_format(args.output_format)
    image_paths = [Path(value) for value in args.image]
    for path in image_paths:
        if not path.exists():
            die(f"image file not found: {path}")
    if args.mask and not Path(args.mask).exists():
        die(f"mask file not found: {args.mask}")

    payload = {
        "model": args.model,
        "prompt": prompt,
        "size": args.size,
        "quality": args.quality,
        "n": args.n,
        "output_format": output_format,
    }
    if args.background:
        payload["background"] = args.background
    if args.input_fidelity:
        payload["input_fidelity"] = args.input_fidelity
    if args.output_compression is not None:
        payload["output_compression"] = args.output_compression

    output_paths = build_output_paths(args.out, output_format, args.n)
    ensure_writable(output_paths, args.force)

    if args.dry_run:
        preview = dict(payload)
        preview["image"] = [str(path) for path in image_paths]
        if args.mask:
            preview["mask"] = args.mask
        print_request("/images/edits", preview, output_paths, base_url, args.api_key_env)
        return

    client = create_client(api_key, base_url)
    print(f"Calling {base_url or 'default OpenAI endpoint'} /images/edits", file=sys.stderr)
    handles = [path.open("rb") for path in image_paths]
    mask_handle = Path(args.mask).open("rb") if args.mask else None
    try:
        request = dict(payload)
        request["image"] = handles if len(handles) > 1 else handles[0]
        if mask_handle is not None:
            request["mask"] = mask_handle
        result = client.images.edit(**request)
    finally:
        for handle in handles:
            handle.close()
        if mask_handle is not None:
            mask_handle.close()
    write_results(result, output_paths)


def add_shared_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-url", help="OpenAI-compatible base URL, for example https://host/v1")
    parser.add_argument("--api-key-env", default="OPENAI_API_KEY", help="environment variable name that stores the API key")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--prompt")
    parser.add_argument("--prompt-file")
    parser.add_argument("--size", default=DEFAULT_SIZE)
    parser.add_argument("--quality", default=DEFAULT_QUALITY)
    parser.add_argument("--background")
    parser.add_argument("--output-format")
    parser.add_argument("--output-compression", type=int)
    parser.add_argument("--n", type=int, default=1)
    parser.add_argument("--out", required=True)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--dry-run", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Image generation CLI for OpenAI-compatible relay providers")
    subparsers = parser.add_subparsers(dest="command", required=True)

    gen = subparsers.add_parser("generate", help="generate a new image")
    add_shared_args(gen)
    gen.set_defaults(func=run_generate)

    edit = subparsers.add_parser("edit", help="edit one or more images")
    add_shared_args(edit)
    edit.add_argument("--image", action="append", required=True)
    edit.add_argument("--mask")
    edit.add_argument("--input-fidelity")
    edit.set_defaults(func=run_edit)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.n < 1 or args.n > 10:
        die("--n must be between 1 and 10")
    if args.output_compression is not None and not (0 <= args.output_compression <= 100):
        die("--output-compression must be between 0 and 100")
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())