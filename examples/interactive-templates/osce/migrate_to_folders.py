#!/usr/bin/env python3
"""Convert an exported legacy OSCE library into folder references and a reduced native archive.

Never edits the input backup or the device. Copy and verify references before importing the
result with Replace for Memories and Templates. No downloads or model calls are made.
"""
import argparse
import hashlib
import json
import unicodedata
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).parent
SKILLS = 'memories/skill_db/'
EXAMINER = 'zero-to-finals-osce.md'


def component(value):
    value = unicodedata.normalize('NFC', value)
    if not value or value in {'.', '..'} or any(c in value for c in '/\\') or any(ord(c) < 32 for c in value):
        raise ValueError('Unsafe reference filename: ' + repr(value))
    return value


def prepare(backup, destination, source_directory='/mnt/shared/Zero to Finals'):
    if not (source_directory == '/mnt/shared/Zero to Finals' or source_directory.startswith('/mnt/shared/')):
        raise ValueError('Use an Alpine shared-storage directory')
    if any(p in {'.', '..'} for p in source_directory.split('/')):
        raise ValueError('Unsafe source directory')
    catalogue = json.loads((ROOT / 'catalogue.json').read_text())
    mapping = {}
    for subject, groups in catalogue.items():
        for specialty, topics in groups.items():
            for topic in topics:
                relative = '/'.join(map(component, [subject, specialty, topic['title'] + '.md']))
                if topic['file'] in mapping or relative.casefold() in {p.casefold() for p in mapping.values()}:
                    raise ValueError('Duplicate source mapping')
                mapping[topic['file']] = relative
    with ZipFile(backup) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        if any(n.startswith('/') or '..' in PurePosixPath(n).parts or '\\' in n for n in names):
            raise ValueError('Unsafe archive entry')
        entries = {n: archive.read(n) for n in names if not n.endswith('/')}
    manifest = json.loads(entries['manifest.json'])
    if set(manifest['categories']) != {'memories', 'templates'} or manifest.get('has_api_keys'):
        raise ValueError('Export only Memories and Templates, without credentials')
    if any(not (n == 'manifest.json' or n == 'templates/catalog.json' or n.startswith('memories/')) for n in entries):
        raise ValueError('Unexpected archive category')
    present = {n.removeprefix(SKILLS) for n in entries if n.startswith(SKILLS + 'ztf-') and n.endswith('.md')}
    if present != set(mapping):
        raise ValueError('Legacy references do not match the original catalogue; nothing was moved')
    sources = {relative: entries[SKILLS + old] for old, relative in mapping.items()}
    if any(not body.strip() for body in sources.values()):
        raise ValueError('An exported source is empty')
    if SKILLS + EXAMINER not in entries:
        raise ValueError('Original examiner Skill is missing')
    templates = json.loads(entries['templates/catalog.json'])
    candidates = [t for t in templates if t.get('linkedSkill') == EXAMINER]
    if len(candidates) != 1:
        raise ValueError('Expected one original linked OSCE setup template')
    original = candidates[0]
    setup = json.loads((ROOT / 'osce.agora-template.json').read_text())
    setup.update(id=original['id'], name=original['name'], sourceDirectory=source_directory)
    setup.pop('sourceFolderId', None)
    setup.pop('shortcut', None)
    setup.pop('linkedSkill', None)
    shortcut = original.get('shortcut') or 'osce'
    if any(t is not original and t.get('shortcut') == shortcut for t in templates):
        raise ValueError('Shortcut is owned by another template')
    metadata = json.loads(entries.get(SKILLS + 'skill_meta.json', b'{}'))
    if any(k.startswith('@shortcut:') and k != '@shortcut:' + EXAMINER and v == shortcut for k, v in metadata.items()):
        raise ValueError('Shortcut is owned by another Skill')
    configuration = dict(shortcut=shortcut, beforeTemplate=setup['id'])
    examiner = '<!-- agora-skill/v1\n' + json.dumps(configuration, separators=(',', ':')) + '\n-->\n' + (ROOT / EXAMINER).read_text()
    retained = {n: b for n, b in entries.items() if n not in {SKILLS + old for old in mapping}}
    retained[SKILLS + EXAMINER] = examiner.encode()
    for old in mapping:
        metadata.pop(old, None)
        metadata.pop('@shortcut:' + old, None)
    metadata.update({EXAMINER: 'Practise a selected Zero to Finals condition',
                     '@shortcut:' + EXAMINER: shortcut})
    retained[SKILLS + 'skill_meta.json'] = json.dumps(metadata).encode()
    retained['templates/catalog.json'] = json.dumps([setup if t is original else t for t in templates], ensure_ascii=False).encode()
    manifest['exported_at'] = datetime.now(timezone.utc).isoformat()
    retained['manifest.json'] = json.dumps(manifest).encode()
    folder = destination / 'Zero to Finals'
    # Preflight every destination before writing anything. Differing existing files are never replaced.
    for relative, body in sources.items():
        path = folder / relative
        if path.is_symlink() or path.exists() and (not path.is_file() or path.read_bytes() != body):
            raise ValueError('Destination differs: ' + str(path))
        if folder.resolve() not in path.resolve().parents:
            raise ValueError('Destination escaped reference folder')
    output = destination / 'zero-to-finals-folder-workflow.agora'
    if output.exists():
        raise ValueError('Output archive already exists; choose a new destination')
    for relative, body in sources.items():
        path = folder / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_bytes(body)
    destination.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
        for name, body in retained.items():
            archive.writestr(name, body)
    audit = [{'old': old, 'path': relative, 'sha256': hashlib.sha256(sources[relative]).hexdigest()}
             for old, relative in mapping.items()]
    (destination / 'migration-audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n')
    return folder, output, audit


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    folder, archive, audit = prepare(args.backup, args.destination)
    print(f'Prepared {len(audit)} unchanged references in {folder}; native workflow: {archive}')
