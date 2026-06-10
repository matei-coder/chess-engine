#!/usr/bin/env python3
"""Interactive UCI session with live style switching.

Comenzi custom (în plus față de UCI standard):
  list / styles            — listează stilurile disponibile
  style <nume_sau_număr>   — încarcă stilul ales (setoption StyleFile)
  bench [poziție]          — rulează go depth 8 pe o poziție test, afișează scor+PV
  bench startpos           — bench pe poziția inițială (default e Kiwipete)
  compare <styleA> <styleB> — bench pe fiecare stil consecutiv, arată diferența
  help                     — afișează aceste comenzi
  quit / exit              — ieșire

Orice altă comandă e trimisă direct engine-ului ca UCI.
"""
import glob
import json
import os
import select
import subprocess
import sys
import time

REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STYLES_DIR = os.path.join(REPO_DIR, "styles")
ENGINE = os.path.join(REPO_DIR, "run_engine.sh")

# Pozitie default pentru bench (Kiwipete — tactic, ne-balansat)
BENCH_DEFAULT_FEN = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"


class Session:
    def __init__(self):
        self.styles = self._load_styles()
        self.current_style = None
        self.engine = subprocess.Popen(
            [ENGINE],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        self._handshake()

    def _load_styles(self):
        styles = {}
        for f in sorted(glob.glob(os.path.join(STYLES_DIR, "*.json"))):
            name = os.path.basename(f)[:-5]  # strip .json
            if name in ("current", "vector_sample"):
                continue
            try:
                with open(f) as fp:
                    meta = json.load(fp)
                desc = meta.get("description", "")
                styles[name] = {"path": f, "description": desc}
            except (json.JSONDecodeError, OSError):
                pass
        return styles

    def _send(self, cmd):
        self.engine.stdin.write(cmd + "\n")
        self.engine.stdin.flush()

    def _read_until(self, marker, timeout=10.0):
        """Read lines until one starts with `marker`. Returns list of lines."""
        deadline = time.time() + timeout
        lines = []
        while time.time() < deadline:
            remaining = deadline - time.time()
            ready, _, _ = select.select([self.engine.stdout], [], [], remaining)
            if not ready:
                continue
            line = self.engine.stdout.readline()
            if not line:
                break
            line = line.rstrip("\n")
            lines.append(line)
            print(line)
            if line.startswith(marker):
                break
        return lines

    def _handshake(self):
        print("Starting engine handshake...")
        self._send("uci")
        self._read_until("uciok", timeout=5.0)
        self._send("isready")
        self._read_until("readyok", timeout=5.0)

    def list_styles(self):
        if not self.styles:
            print("(no styles found in styles/)")
            return
        print("\nStiluri disponibile:")
        for i, (name, data) in enumerate(self.styles.items(), 1):
            marker = " *" if name == self.current_style else "  "
            print(f"{marker}{i:2}) {name:14} — {data['description']}")
        print()

    def pick_style(self, choice):
        choice = choice.strip()
        if not choice:
            print("Usage: style <name_or_number>")
            return
        name = None
        if choice.isdigit():
            idx = int(choice) - 1
            keys = list(self.styles.keys())
            if 0 <= idx < len(keys):
                name = keys[idx]
        else:
            if choice in self.styles:
                name = choice
        if name is None:
            print(f"Unknown style: {choice}. Try 'list' to see available.")
            return
        path = os.path.abspath(self.styles[name]["path"])
        self._send(f"setoption name StyleFile value {path}")
        # Engine emite "info string style loaded ..."; citim până vedem
        self._drain_info(timeout=1.0)
        self.current_style = name
        print(f"✓ Active style: {name}")

    def _drain_info(self, timeout=0.5):
        deadline = time.time() + timeout
        while time.time() < deadline:
            remaining = deadline - time.time()
            ready, _, _ = select.select([self.engine.stdout], [], [], remaining)
            if not ready:
                break
            line = self.engine.stdout.readline()
            if not line:
                break
            print(line.rstrip("\n"))

    def bench(self, arg):
        fen = arg.strip() if arg.strip() and arg.strip() != "startpos" else None
        if arg.strip() == "startpos":
            pos = "position startpos"
        elif fen:
            pos = f"position fen {fen}"
        else:
            pos = f"position fen {BENCH_DEFAULT_FEN}"
        print(f"[bench] {pos}")
        self._send("setoption name OwnBook value false")
        self._send(pos)
        self._send("go depth 8")
        self._read_until("bestmove", timeout=30.0)
        # Re-enable book for normal use
        self._send("setoption name OwnBook value true")

    def compare(self, args):
        parts = args.split()
        if len(parts) != 2:
            print("Usage: compare <styleA> <styleB>")
            return
        for s in parts:
            print(f"\n=== {s} ===")
            self.pick_style(s)
            self.bench("")
        print()

    def help(self):
        print(__doc__)

    def loop(self):
        print("\nReady. Tasteaza 'help' pentru comenzi, 'list' pentru stiluri.")
        print("Orice text necunoscut se trimite ca UCI command la engine.")
        self.list_styles()
        while True:
            try:
                cmd = input("chess> ").strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
            if not cmd:
                continue
            if cmd in ("quit", "exit"):
                self._send("quit")
                break
            if cmd in ("list", "styles"):
                self.list_styles()
                continue
            if cmd == "help":
                self.help()
                continue
            if cmd.startswith("style "):
                self.pick_style(cmd[6:])
                continue
            if cmd == "bench":
                self.bench("")
                continue
            if cmd.startswith("bench "):
                self.bench(cmd[6:])
                continue
            if cmd.startswith("compare "):
                self.compare(cmd[8:])
                continue
            # Passthrough UCI
            self._send(cmd)
            # Read until natural end-of-response (bestmove, readyok, uciok, or timeout)
            if cmd.startswith("go"):
                self._read_until("bestmove", timeout=60.0)
            elif cmd == "isready":
                self._read_until("readyok", timeout=5.0)
            elif cmd == "uci":
                self._read_until("uciok", timeout=5.0)
            else:
                self._drain_info(timeout=0.3)

        try:
            self.engine.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.engine.kill()


if __name__ == "__main__":
    Session().loop()
