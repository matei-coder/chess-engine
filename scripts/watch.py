#!/usr/bin/env python3
"""Watch — live monitor pentru schimbari de stil + engine activity.

Două surse de evenimente:
  1. styles/current.json (poll mtime) — vezi când brain/pick scrie file nou
  2. lichess-bot.log (tail) — vezi când engine auto-reloadează stilul

Output: timestamp + sursă + ce s-a schimbat (cu diff vs precedent).
ANSI colors când e TTY.

Usage:
    ./scripts/watch.sh                 # default — watch ambele
    ./scripts/watch.sh --no-bot        # doar style file
    ./scripts/watch.sh --bot-log PATH  # custom path către lichess-bot.log
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Optional

REPO    = Path(__file__).resolve().parent.parent
CURRENT = REPO / "styles" / "current.json"

# Default bot log paths to try
BOT_LOG_CANDIDATES = [
    Path.home() / "lichess-bot" / "lichess_bot_auto_logs" / "lichess-bot.log",
    REPO / "lichess_bot_auto_logs" / "lichess-bot.log",
]


# --- ANSI colors (only if stdout is a TTY) ---
def _color_enabled() -> bool:
    return sys.stdout.isatty()

if _color_enabled():
    C_RESET   = "\033[0m"
    C_BOLD    = "\033[1m"
    C_DIM     = "\033[2m"
    C_RED     = "\033[31m"
    C_GREEN   = "\033[32m"
    C_YELLOW  = "\033[33m"
    C_BLUE    = "\033[34m"
    C_MAGENTA = "\033[35m"
    C_CYAN    = "\033[36m"
else:
    C_RESET = C_BOLD = C_DIM = C_RED = C_GREEN = C_YELLOW = C_BLUE = C_MAGENTA = C_CYAN = ""


def ts() -> str:
    return time.strftime("%H:%M:%S")


# --- Style file watcher ---
class StyleFileWatcher:
    def __init__(self, path: Path):
        self.path = path
        self.last_mtime: Optional[float] = None
        self.last_modifiers: Optional[dict] = None
        self.last_description: Optional[str] = None
        self._init()

    def _init(self):
        if self.path.exists():
            self.last_mtime = self.path.stat().st_mtime
            try:
                data = json.loads(self.path.read_text())
                self.last_modifiers = data.get("modifiers", {}) if isinstance(data.get("modifiers"), dict) else None
                self.last_description = data.get("description", "")
            except (json.JSONDecodeError, OSError):
                pass

    def check(self):
        if not self.path.exists():
            return
        mt = self.path.stat().st_mtime
        if self.last_mtime is not None and mt == self.last_mtime:
            return
        self.last_mtime = mt

        try:
            data = json.loads(self.path.read_text())
        except json.JSONDecodeError as e:
            print(f"{C_DIM}[{ts()}]{C_RESET} {C_RED}STYLE PARSE ERROR{C_RESET}: {e}")
            return
        except OSError:
            return

        desc = data.get("description", "")
        modifiers = data.get("modifiers", {})
        is_vector = isinstance(modifiers, list)

        # Header
        print()
        print(f"{C_DIM}[{ts()}]{C_RESET} {C_BOLD}{C_CYAN}STYLE CHANGED{C_RESET} → {C_BLUE}{self.path.name}{C_RESET}")
        if desc != self.last_description:
            print(f"  {C_BOLD}desc:{C_RESET} {desc}")
        else:
            print(f"  {C_DIM}desc:{C_RESET} {desc}")

        if is_vector:
            print(f"  modifiers: vector form ({len(modifiers)} floats)")
            non_unit = sum(1 for v in modifiers if abs(v - 1.0) > 0.001)
            print(f"  {non_unit} entries differ from 1.0")
        else:
            # Diff named modifiers
            current = modifiers
            previous = self.last_modifiers or {}
            all_keys = sorted(set(current) | set(previous))

            if not all_keys:
                print(f"  {C_DIM}(no modifiers — equivalent to baseline){C_RESET}")
            else:
                print(f"  {C_BOLD}modifiers:{C_RESET}")
                for k in all_keys:
                    old = previous.get(k)
                    new = current.get(k)
                    if old == new:
                        # Unchanged — show dim
                        print(f"    {C_DIM}{k:18} = {new}{C_RESET}")
                    elif old is None:
                        # Added
                        print(f"    {C_GREEN}+ {k:16} = {new}{C_RESET}  {C_DIM}(new){C_RESET}")
                    elif new is None:
                        # Removed
                        print(f"    {C_RED}- {k:16} = {old}{C_RESET}  {C_DIM}(removed → 1.0){C_RESET}")
                    else:
                        # Changed
                        delta = new - old
                        arrow = "↑" if delta > 0 else "↓"
                        color = C_GREEN if delta > 0 else C_RED
                        print(f"    {C_YELLOW}~ {k:16} = {old} {arrow} {new}{C_RESET}  {color}({delta:+.3f}){C_RESET}")

            self.last_modifiers = current

        self.last_description = desc


# --- Bot log tailer ---
class BotLogTailer:
    def __init__(self, path: Path):
        self.path = path
        self.pos = path.stat().st_size if path.exists() else 0

    def check(self):
        if not self.path.exists():
            return
        try:
            cur_size = self.path.stat().st_size
            # File was rotated/truncated?
            if cur_size < self.pos:
                self.pos = 0
            if cur_size == self.pos:
                return
            with open(self.path, "r", errors="replace") as f:
                f.seek(self.pos)
                while True:
                    line = f.readline()
                    if not line:
                        self.pos = f.tell()
                        break
                    line = line.rstrip()
                    self._maybe_print(line)
        except OSError:
            pass

    def _maybe_print(self, line: str):
        # Filtreaza doar evenimentele de stil + bestmove + game start/end
        lower = line.lower()
        if "info string style" in lower:
            # Engine confirms a style change
            color = C_GREEN if "auto-reloaded" in lower else (C_RED if "failed" in lower or "invalid" in lower else C_CYAN)
            print(f"{C_DIM}[{ts()}]{C_RESET} {color}ENGINE{C_RESET}: {line}")
        elif "info string book" in lower:
            # Book hit/miss — useful context
            print(f"{C_DIM}[{ts()}]{C_RESET} {C_MAGENTA}ENGINE{C_RESET}: {line}")
        elif "started game" in lower or "finished game" in lower:
            print(f"{C_DIM}[{ts()}]{C_RESET} {C_BOLD}{C_YELLOW}GAME{C_RESET}: {line}")
        elif "bestmove" in lower and "engine.py" not in lower:  # avoid debug log noise
            pass  # ignore raw bestmoves


# --- Main ---
def main():
    ap = argparse.ArgumentParser(description="Watch live for style changes")
    ap.add_argument("--no-bot", action="store_true", help="Doar style file, ignoră bot log")
    ap.add_argument("--bot-log", type=Path, default=None, help="Path către lichess-bot.log (auto-detected dacă lipsește)")
    args = ap.parse_args()

    style_watcher = StyleFileWatcher(CURRENT)

    bot_tailer = None
    if not args.no_bot:
        candidates = [args.bot_log] if args.bot_log else BOT_LOG_CANDIDATES
        for p in candidates:
            if p and p.exists():
                bot_tailer = BotLogTailer(p)
                break

    # Banner
    print(f"{C_BOLD}── style watch ──{C_RESET}")
    print(f"  style file: {C_BLUE}{CURRENT.relative_to(REPO)}{C_RESET}")
    if bot_tailer:
        print(f"  bot log:    {C_BLUE}{bot_tailer.path}{C_RESET}")
    elif not args.no_bot:
        print(f"  bot log:    {C_DIM}(not found — only watching style file){C_RESET}")
    print(f"  poll:       {C_DIM}500ms{C_RESET}")
    print(f"  Ctrl-C to quit.")
    print()

    # Show initial state
    print(f"{C_DIM}[{ts()}] initial state:{C_RESET}")
    if CURRENT.exists():
        try:
            data = json.loads(CURRENT.read_text())
            print(f"  desc: {data.get('description', '')}")
            mods = data.get('modifiers', {})
            if isinstance(mods, dict):
                print(f"  {len(mods)} modifiers")
            elif isinstance(mods, list):
                print(f"  vector of {len(mods)} floats")
        except (json.JSONDecodeError, OSError) as e:
            print(f"  {C_RED}can't read: {e}{C_RESET}")
    else:
        print(f"  {C_YELLOW}{CURRENT} doesn't exist yet{C_RESET}")
    print()

    # Reset style_watcher's initial state so first change is detected, not initial load
    # (we already printed initial above)

    try:
        while True:
            style_watcher.check()
            if bot_tailer:
                bot_tailer.check()
            time.sleep(0.5)
    except KeyboardInterrupt:
        print()
        print(f"{C_DIM}bye.{C_RESET}")


if __name__ == "__main__":
    main()
