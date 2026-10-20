#!/usr/bin/env python3
"""Package only reusable interfaces and Skills. References remain in the user's Android-selected folder."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).parent

def build(destination):
    templates = [json.loads((ROOT / name).read_text()) for name in
                 ('osce.agora-template.json', 'review.agora-template.json')]
    destination.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(destination, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('manifest.json', json.dumps({'agora_export_version': 6, 'app_version': '2.1.0', 'exported_at': '2026-10-17T00:00:00Z', 'categories': ['memories', 'templates'], 'has_api_keys': False}))
        archive.writestr('templates/catalog.json', json.dumps(templates))
        archive.writestr('memories/skill_db/osce.md', (ROOT / 'examiner.md').read_text())
        archive.writestr('memories/skill_db/targeted-revision.md', (ROOT / 'targeted-revision.md').read_text())
        archive.writestr('memories/skill_db/skill_meta.json', json.dumps({
            'osce.md': 'Practise a reference based clinical examination',
            'targeted-revision.md': 'Revise gaps identified in the examination',
            '@shortcut:osce.md': 'osce',
        }))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
