#!/usr/bin/env python3
"""One-shot label taxonomy migration for Backlog.md tasks.

Old free-form labels -> closed slash-namespaced vocabulary:
  kind/  exactly one per task (work type)
  area/  1-2 per task (subsystem)
  model/ only for model-specific tasks

Usage:
  migrate-labels.py [--apply]      # default: dry-run report
"""
import re
import sys
from pathlib import Path

TASKS = Path(__file__).resolve().parent.parent / "backlog" / "tasks"

# old label -> new label. Lowercase keys; comparison is case-insensitive.
MAP = {
    # kind/bug
    "bug": "kind/bug", "bug-risk": "kind/bug", "crash": "kind/bug",
    "native-crash": "kind/bug", "correctness": "kind/bug",
    # kind/feature
    "feature": "kind/feature", "core-feature": "kind/feature",
    "future": "kind/feature",
    # kind/enhancement
    "enhancement": "kind/enhancement", "ux-improvement": "kind/enhancement",
    "polish": "kind/enhancement", "quick-win": "kind/enhancement",
    "quality": "kind/enhancement", "feature-flag": "kind/feature",
    # kind/performance
    "performance": "kind/performance", "optimization": "kind/performance",
    "memory": "kind/performance", "perf": "kind/performance",
    # kind/refactor
    "refactor": "kind/refactor", "refactoring": "kind/refactor",
    "refactor-prep": "kind/refactor", "tech-debt": "kind/refactor",
    "architecture": "kind/refactor",
    "cleanup": "kind/refactor", "hygiene": "kind/refactor",
    "duplication": "kind/refactor", "maintainability": "kind/refactor",
    # kind/test
    "testing": "kind/test", "test": "kind/test", "unit-test": "kind/test",
    "unit-tests": "kind/test", "integration": "kind/test",
    "integration-tests": "kind/test", "qa": "kind/test", "flaky": "kind/test",
    # kind/docs
    "docs": "kind/docs", "documentation": "kind/docs", "content": "kind/docs",
    "store-listing": "kind/docs", "marketing": "kind/docs", "branding": "kind/docs",
    # kind/research
    "research": "kind/research", "benchmark": "kind/research",
    "evaluation": "kind/research", "eval": "kind/research",
    "spike": "kind/research", "investigation": "kind/research",
    "scout": "kind/research", "model-evaluation": "kind/research",
    "reddit": "kind/docs",
    # kind/chore
    "chore": "kind/chore", "setup": "kind/chore", "build": "kind/chore",
    "configuration": "kind/chore", "config": "kind/chore",
    "dependencies": "kind/chore", "dependency": "kind/chore", "deps": "kind/chore",
    "upgrade": "kind/chore", "framework": "kind/chore", "framework-upgrade": "kind/chore",
    "infrastructure": "kind/chore", "automation": "kind/chore", "git": "kind/chore",
    "reproducibility": "kind/chore", "mirror": "kind/chore",
    "compliance": "kind/chore", "decision": "kind/chore",
    "fastlane": "kind/chore",
    # kind/breaking
    "breaking-change": "kind/breaking",
    # area/transcription
    "transcription": "area/transcription", "transducer": "area/transcription",
    "streaming": "area/transcription", "inference": "area/transcription",
    "vad": "area/transcription", "audio": "area/transcription",
    "audio-processing": "area/transcription", "audio-preprocessing": "area/transcription",
    "audio-pipeline": "area/transcription", "asr": "area/transcription",
    "subtitles": "area/transcription", "transcription-quality": "area/transcription",
    "fine-tuning": "area/transcription", "backend": "area/transcription",
    "llm": "area/transcription", "external-models": "area/transcription",
    "custom-model": "area/transcription", "dispatch-site": "area/transcription",
    # area/ui
    "ui": "area/ui", "ux": "area/ui", "material3": "area/ui",
    "theming": "area/ui", "navigation": "area/ui", "scroll": "area/ui",
    "model-tab": "area/ui", "logs-tab": "area/ui", "logs": "area/ui",
    "screenshots": "area/ui", "copy": "area/ui", "compose": "area/ui",
    "layout": "area/ui", "feedback": "area/ui", "onboarding": "area/ui",
    # area/notifications
    "notifications": "area/notifications", "notification": "area/notifications",
    "in-progress-notif": "area/notifications",
    # area/data
    "datastore": "area/data", "persistence": "area/data", "preferences": "area/data",
    "viewmodel": "area/data", "storage": "area/data",
    "state-management": "area/data", "file-handling": "area/data",
    # area/settings
    "settings": "area/settings", "defaults": "area/settings",
    "advanced": "area/settings",
    # area/downloads
    "downloads": "area/downloads", "download": "area/downloads",
    "model-download": "area/downloads", "download-resume": "area/downloads",
    "model-management": "area/downloads", "model-selection": "area/downloads",
    "model": "area/downloads", "models": "area/downloads",
    "model-update": "area/downloads", "model-support": "area/downloads",
    "catalog": "area/downloads", "huggingface": "area/downloads",
    "authentication": "area/downloads", "auth": "area/downloads",
    "oauth": "area/downloads", "token-refresh": "area/downloads",
    "network": "area/downloads", "networking": "area/downloads",
    "extraction": "area/downloads", "sideload": "area/downloads",
    # area/receiver
    "sharing": "area/receiver", "share-intent": "area/receiver",
    "share-target": "area/receiver", "share-back": "area/receiver",
    "intent": "area/receiver", "broadcast": "area/receiver",
    "broadcast-receiver": "area/receiver", "tasker": "area/receiver",
    "deep-link": "area/receiver", "app-detection": "area/receiver",
    # area/i18n
    "i18n": "area/i18n", "localization": "area/i18n", "italian": "area/i18n",
    "russian": "area/i18n",
    # area/build
    "native": "area/build", "native-libraries": "area/build",
    "manifest": "area/build", "kotlin": "area/build", "hilt": "area/build",
    "litert": "area/build", "litert-lm": "area/build", "r8": "area/build",
    "crashlytics": "area/ci",
    # area/release
    "release": "area/release", "play-store": "area/release",
    "play-store-feedback": "area/release", "fdroid": "area/release",
    "f-droid": "area/release", "release-blocker": "area/release",
    # area/ci
    "ci": "area/ci", "ci-cd": "area/ci",
    # area/platform (OS/device/runtime behavior)
    "android": "area/platform", "android-13": "area/platform",
    "android-15": "area/platform", "android-16": "area/platform",
    "device-compat": "area/platform", "background": "area/platform",
    "service": "area/platform", "startup": "area/platform",
    "keep-alive": "area/platform", "permissions": "area/platform",
    "security": "area/platform", "privacy": "area/platform",
    "safety": "area/platform", "watch": "area/platform", "watchlist": "area/platform",
    "battery": "area/platform", "compatibility": "area/platform",
    "deprecated-api": "area/platform", "android-12+": "area/platform",
    "foreground-service": "area/platform",
    # area/reliability (cross-cutting robustness concerns)
    "reliability": "area/reliability", "robustness": "area/reliability",
    "error-handling": "area/reliability",
    # model/*
    "parakeet": "model/parakeet", "whisper": "model/whisper",
    "gemma": "model/gemma", "qwen": "model/qwen", "qwen3": "model/qwen",
    "nemotron": "model/nemotron", "gigaam": "model/gigaam",
    "sherpa-onnx": "model/sherpa", "onnx": "model/sherpa", "qnn": "model/sherpa",
    "model/external": "model/external", "multi-family": "model/external",
    "ctc": "model/external", "mms": "model/external",
    "litertlm": "model/gemma",
}

# Labels that are data errors or duplicates of dedicated fields: drop them.
DROP = {
    "github-issue", "triage", "follow-up", "help", "video",
    "production", "in-progress", ">-", "profiles", "manual-override",
    "high-priority", "phase-1", "phase-2", "phase-3", "phase-4", "phase-5",
}

LABEL_LINE = re.compile(r'^(\s*)-\s*(.+)$')


def is_garbage(label: str) -> bool:
    l = label.strip().strip("'\"")
    return (l.startswith("http") or l.startswith("TASK-")
            or l.startswith("memory/") or l == "")


def migrate(apply: bool) -> int:
    unknown: dict[str, int] = {}
    dropped: dict[str, int] = {}
    changed = 0
    for f in sorted(TASKS.glob("*.md")):
        text = f.read_text()
        lines = text.split("\n")
        out = []
        in_labels = False
        touched = False
        for line in lines:
            if re.match(r'^labels:\s*$', line):
                in_labels = True
                out.append(line)
                continue
            if in_labels:
                m = re.match(r'^(\s*)-\s*(.+)$', line)
                if m and not line.strip().startswith("#"):
                    raw = m.group(2).strip().strip("'\"")
                    if is_garbage(raw):
                        dropped[raw] = dropped.get(raw, 0) + 1
                        touched = True
                        continue
                    key = raw.lower()
                    if key in MAP:
                        new = MAP[key]
                    elif key in DROP:
                        dropped[raw] = dropped.get(raw, 0) + 1
                        touched = True
                        continue
                    else:
                        unknown[raw] = unknown.get(raw, 0) + 1
                        new = raw  # leave as-is for manual review
                    if new != raw:
                        touched = True
                    indent = m.group(1)
                    out.append(f"{indent}- {new}")
                    continue
                in_labels = False
            out.append(line)
        # dedupe while preserving order
        if touched:
            seen_labels = False
            deduped = []
            for line in out:
                m = re.match(r'^labels:\s*$', line)
                if m:
                    seen_labels = True
                    deduped.append(line)
                    continue
                if seen_labels:
                    lm = re.match(r'^(\s*)-\s*(.+)$', line)
                    if lm and lm.group(2).strip() in [d.split("- ", 1)[-1] for d in deduped[-8:]]:
                        continue
                deduped.append(line)
            new_text = "\n".join(deduped)
            changed += 1
            if apply:
                f.write_text(new_text)
    print(f"{'APPLIED' if apply else 'DRY-RUN'}: {changed} task files touched")
    if dropped:
        print("\nDROPPED (data errors / field duplicates):")
        for k, v in sorted(dropped.items(), key=lambda x: -x[1]):
            print(f"  {v:3d}  {k}")
    if unknown:
        print("\nUNKNOWN (left as-is, need manual mapping):")
        for k, v in sorted(unknown.items(), key=lambda x: -x[1]):
            print(f"  {v:3d}  {k}")
    else:
        print("\nNo unknown labels.")
    return 0


if __name__ == "__main__":
    sys.exit(migrate(apply="--apply" in sys.argv))
