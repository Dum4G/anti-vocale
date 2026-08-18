#!/usr/bin/env python3
"""Validate Play Store listing character limits."""

import re
import sys

STORE_LISTING = "docs/play-store/store-listing.md"
SHORT_DESC_LIMIT = 80
FULL_DESC_LIMIT = 4000


def strip_markdown(text: str) -> str:
    """Remove markdown formatting for accurate Play Store char count."""
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = text.replace("•", "-")
    text = text.replace("|", " ")
    return text


def extract_sections(content: str) -> dict[str, str]:
    """Extract short and full descriptions from the listing file."""
    sections = {}

    # Short description (EN)
    m = re.search(r"## Short Description.*?```\n(.*?)\n```", content, re.DOTALL)
    if m:
        sections["short_en"] = m.group(1).strip()

    # Short description (IT) — may be under ### or ## heading
    m = re.search(r"(?:###\s*)?Short Description.*?```\n(.*?)\n```", content, re.DOTALL)
    if m:
        sections["short_it"] = m.group(1).strip()

    # Full description (EN) — from "## Full Description" to the Italian separator
    m = re.search(
        r"## Full Description.*?\n\n(.*?)\n\n---\n\n## Descrizione Breve",
        content,
        re.DOTALL,
    )
    if m:
        sections["full_en"] = m.group(1).strip()

    # Italian section — from "Italiano / Italian:" to the metadata separator
    m = re.search(
        r"Italiano / Italian:\n\n(.*?)\n\n---\n",
        content,
        re.DOTALL,
    )
    if m:
        sections["full_it"] = m.group(1).strip()

    return sections


def check(text: str, label: str, limit: int) -> bool:
    plain = strip_markdown(text)
    length = len(plain)
    ok = length <= limit
    status = "OK" if ok else "OVER"
    print(f"  {label}: {length}/{limit} [{status}]")
    if not ok:
        print(f"    Exceeds by {length - limit} characters")
    return ok


def main():
    try:
        with open(STORE_LISTING) as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: {STORE_LISTING} not found")
        sys.exit(1)

    sections = extract_sections(content)
    all_ok = True

    print("Short descriptions:")
    if "short_en" in sections:
        all_ok &= check(sections["short_en"], "EN", SHORT_DESC_LIMIT)
    else:
        print("  EN: NOT FOUND")
        all_ok = False
    if "short_it" in sections:
        all_ok &= check(sections["short_it"], "IT", SHORT_DESC_LIMIT)
    else:
        print("  IT: NOT FOUND")
        all_ok = False

    print("\nFull descriptions (individual):")
    if "full_en" in sections:
        all_ok &= check(sections["full_en"], "EN", FULL_DESC_LIMIT)
    else:
        print("  EN: NOT FOUND")
        all_ok = False
    if "full_it" in sections:
        all_ok &= check(sections["full_it"], "IT", FULL_DESC_LIMIT)
    else:
        print("  IT: NOT FOUND")
        all_ok = False

    # No combined check: Play enforces the 4000-char limit PER LOCALE (each
    # listing language is its own field). The old EN+IT sum check silently
    # rejected valid longer listings once both locales grew past ~2000 chars.

    print()
    if all_ok:
        print("All checks passed.")
    else:
        print("Some checks FAILED.")
        sys.exit(1)


if __name__ == "__main__":
    main()
