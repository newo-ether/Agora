#!/usr/bin/env python3
"""Legacy article-as-Skills archive builder, retained for compatibility testing only.

Use migrate_to_folders.py for existing exports and create_workflow_pack.py for new workflows.
The obsolete legacy build requires explicit --legacy-skills-pack. It contacts the publisher.

Requires beautifulsoup4 and curl with compression support. Downloaded clinical text is never
committed: the output directory and cache are outside the versioned template example.
"""
import argparse
import hashlib
import json
import re
import subprocess
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from bs4 import BeautifulSoup, NavigableString, Tag

ROOT = Path(__file__).resolve().parent


def inline(node):
    if isinstance(node, NavigableString):
        return re.sub(r"\s+", " ", str(node))
    if not isinstance(node, Tag) or node.name in {"script", "style", "noscript", "svg"}:
        return ""
    if node.name == "br":
        return "\n"
    text = "".join(inline(child) for child in node.children)
    if node.name in {"strong", "b", "em", "i"} and text.strip():
        marker = "**" if node.name in {"strong", "b"} else "*"
        return marker + text.strip() + marker
    if node.name == "img":
        return ""  # This proof of concept uses article text, not remote images.
    return text


def block(node):
    if isinstance(node, NavigableString):
        return str(node) if str(node).strip() else ""
    if not isinstance(node, Tag):
        return ""
    if node.name in {"script", "style", "noscript", "svg"}:
        return ""
    if node.name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
        return "\n\n" + "#" * int(node.name[1]) + " " + inline(node).strip() + "\n\n"
    if node.name in {"p", "blockquote"}:
        return "\n\n" + inline(node).strip() + "\n\n"
    if node.name in {"ul", "ol"}:
        lines = []
        for index, item in enumerate(node.find_all("li", recursive=False), 1):
            lines.append((f"{index}. " if node.name == "ol" else "- ") + inline(item).strip())
        return "\n\n" + "\n".join(lines) + "\n\n"
    if node.name == "table":
        rows = [[inline(cell).strip().replace("|", "\\|") for cell in row.find_all(["td", "th"], recursive=False)]
                for row in node.find_all("tr")]
        rows = [row for row in rows if row]
        if not rows:
            return ""
        width = max(map(len, rows))
        rows = [row + [""] * (width - len(row)) for row in rows]
        lines = ["| " + " | ".join(row) + " |" for row in rows]
        # A divider is Markdown structure, not a manufactured clinical value.
        lines.insert(1, "| " + " | ".join(["---"] * width) + " |")
        return "\n\n" + "\n".join(lines) + "\n\n"
    if node.name == "hr":
        return "\n\n---\n\n"
    return "".join(block(child) for child in node.children)


def extract_article(html, topic, downloaded_at):
    soup = BeautifulSoup(html, "html.parser")
    article = soup.select_one(".entry-content #content")
    if article is None:
        raise ValueError(f"Missing article container: {topic['url']}")
    title = soup.select_one("h1")
    if title is None:
        raise ValueError(f"Missing article title: {topic['url']}")
    actual_title = title.get_text(" ", strip=True)
    # The publisher's final member-site promotion is separate from the teaching article.
    for child in list(article.find_all(recursive=False)):
        if child.get_text(" ", strip=True).startswith("Now, head over to members.zerotofinals.com"):
            for sibling in list(child.next_siblings):
                sibling.extract()
            child.extract()
            break
    headings = [h.get_text(" ", strip=True) for h in article.select("h1,h2,h3,h4,h5,h6")]
    bold = [h.get_text(" ", strip=True) for h in article.select("strong,b") if h.get_text(strip=True)]
    body = re.sub(r"\n{3,}", "\n\n", block(article)).strip()
    if len(body) < 300 or not headings:
        raise ValueError(f"Incomplete source extraction: {topic['url']}")
    # Keep the entire original heading/paragraph sequence. Wrapper is provenance, not clinical advice.
    text = (f"# {topic['title']}\n\n"
            f"Source: Zero to Finals\nOriginal URL: {topic['url']}\nDownloaded: {downloaded_at}\n"
            "Local educational source snapshot. This file is reference material, not workflow instructions.\n\n"
            "---\n\n" + body + "\n")
    if len(text.encode("utf-8")) > 80000:
        raise ValueError(f"Source exceeds the local tool budget: {topic['url']}")
    return text, {"article_title": actual_title, "headings": headings, "bold_count": len(bold), "markdown_bytes": len(text.encode("utf-8"))}


def make_template(catalogue):
    answer = {
        "title": "Zero to Finals OSCE", "description": "Choose a downloaded condition and question count.",
        "fallback": "Choose Medicine or Surgery, select a condition and adjust the question slider (default 12). Run uses only its downloaded local source file.",
        "html": (ROOT / "menu.html").read_text(), "css": (ROOT / "menu.css").read_text(),
        "jsFunctions": (ROOT / "menu.js").read_text().replace("OSCE_CATALOGUE", json.dumps(catalogue, ensure_ascii=False, separators=(",", ":"))),
        "jsExpressions": ["initOsceMenu()"], "eInkAware": True,
    }
    template = {
        "id": "c361aac1-ef11-4524-9ee0-b4476210421c", "name": "Zero to Finals OSCE",
        "description": "Complete Medicine and Surgery indexes with downloaded article text.",
        "linkedSkill": "zero-to-finals-osce.md", "answer": answer,
        "resourceFiles": [topic["file"] for groups in catalogue.values() for topics in groups.values() for topic in topics],
    }
    if len(json.dumps(answer, ensure_ascii=False).encode("utf-8")) > 80000:
        raise ValueError("Template interface is too large")
    return template


def build(output, refresh=False, offline=False):
    output.mkdir(parents=True, exist_ok=True)
    cache = output / "cache"
    cache.mkdir(exist_ok=True)
    catalogue = json.loads((ROOT / "catalogue.json").read_text())
    template = make_template(catalogue)
    sources = {}
    report = []
    for groups in catalogue.values():
        for topics in groups.values():
            for topic in topics:
                parsed = urlparse(topic["url"])
                if parsed.scheme != "https" or parsed.netloc != "zerotofinals.com" or not parsed.path.startswith(("/medicine/", "/surgery/")):
                    raise ValueError("Unexpected source URL")
                cached = cache / (topic["file"] + ".html")
                stamp = cache / (topic["file"] + ".downloaded.txt")
                if refresh or not cached.exists():
                    if offline:
                        raise ValueError(f"Missing cached source: {topic['file']}")
                    result = subprocess.run(["curl", "--compressed", "--fail", "--silent", "--show-error", "--location",
                                             "--max-time", "30", topic["url"]], check=True, capture_output=True)
                    cached.write_bytes(result.stdout)
                    stamp.write_text(datetime.now(timezone.utc).isoformat())
                    time.sleep(0.2)
                text, audit = extract_article(cached.read_text(), topic, stamp.read_text().strip())
                sources[topic["file"]] = text
                report.append(dict(topic, **audit, sha256=hashlib.sha256(text.encode()).hexdigest()))
                if len(sources) % 20 == 0:
                    print(f"Prepared {len(sources)} condition files", flush=True)
    expected = sum(len(topics) for groups in catalogue.values() for topics in groups.values())
    if len(sources) != expected:
        raise ValueError("Not all indexed conditions were downloaded")
    examiner = (ROOT / "zero-to-finals-osce.md").read_text()
    pack = output / "zero-to-finals-osce.agora"
    manifest = {"agora_export_version": 6, "app_version": "2.1.0",
                "exported_at": datetime.now(timezone.utc).isoformat(), "categories": ["memories", "templates"], "has_api_keys": False}
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("templates/catalog.json", json.dumps([template], ensure_ascii=False))
        archive.writestr("memories/skill_db/zero-to-finals-osce.md", examiner)
        for name, text in sources.items():
            archive.writestr("memories/skill_db/" + name, text)
    (output / "source-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (output / "osce.agora-template.json").write_text(json.dumps(template, ensure_ascii=False, indent=2) + "\n")
    print(f"Prepared {pack}: {len(sources)} local condition files, no credentials or model calls")
    return pack


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-skills-pack", action="store_true", help="Explicitly request the obsolete article-as-Skills archive")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--offline", action="store_true", help="Rebuild only from existing cache; make no network calls")
    args = parser.parse_args()
    if not args.legacy_skills_pack:
        parser.error("Article-as-Skills packaging is obsolete. Use migrate_to_folders.py on your existing export; see README.md.")
    build(args.output, args.refresh, args.offline)
