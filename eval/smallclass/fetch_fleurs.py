#!/usr/bin/env python3
"""Fetch ~10 FLEURS clips per language via the datasets-server rows API (wav URLs)."""
import json, subprocess, sys, os, urllib.request

BASE = "/home/pantinor/data/repo/personal/anti-vocale/eval/smallclass"
N = 10

def fetch(lang_config, lang_dir):
    os.makedirs(f"{BASE}/{lang_dir}", exist_ok=True)
    rows = []
    offset = 0
    while len(rows) < N and offset < 200:
        url = (f"https://datasets-server.huggingface.co/rows?dataset=google/fleurs"
               f"&config={lang_config}&split=validation&offset={offset}&length=10")
        try:
            d = json.load(urllib.request.urlopen(url, timeout=60))
        except Exception as e:
            print(f"{lang_config}: rows API error: {e}")
            return False
        if "error" in d or not d.get("rows"):
            print(f"{lang_config}: {d.get('error', 'no rows')}")
            return False
        for r in d["rows"]:
            row = r["row"]
            if len(rows) >= N:
                break
            audio = row["audio"]
            src = audio[0]["src"] if isinstance(audio, list) else audio["src"]
            idx = r.get("row_idx", len(rows))
            wav = f"{BASE}/{lang_dir}/{lang_dir}_{idx:03d}.wav"
            # re-resample to 16k mono with ffmpeg
            rc = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src,
                                 "-ar", "16000", "-ac", "1", wav]).returncode
            if rc != 0:
                continue
            dur = subprocess.run(["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                                  "-of", "csv=p=0", wav], capture_output=True, text=True).stdout.strip()
            if not dur or float(dur) < 3.0 or float(dur) > 25.0:
                os.remove(wav)
                continue
            rows.append((wav, row["transcription"].strip()))
        offset += 10
    with open(f"{BASE}/{lang_dir}/manifest.tsv", "w") as f:
        for w, t in rows:
            f.write(f"{w}\t{t}\n")
    print(f"{lang_dir}: {len(rows)} clips")
    return len(rows) == N

for cfg, d in [("de_de", "de"), ("fr_fr", "fr"), ("ru_ru", "ru")]:
    fetch(cfg, d)
