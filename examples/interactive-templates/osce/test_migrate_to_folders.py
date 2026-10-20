import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import migrate_to_folders as migration


class FolderMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.backup = self.root / 'before.agora'
        self.destination = self.root / 'after'
        catalogue = json.loads((migration.ROOT / 'catalogue.json').read_text())
        self.references = {topic['file']: ('# ' + topic['title'] + '\nFixture reference\n').encode()
                           for groups in catalogue.values() for topics in groups.values() for topic in topics}
        self.template = dict(id='c361aac1-ef11-4524-9ee0-b4476210421c', name='Zero to Finals OSCE',
                             linkedSkill=migration.EXAMINER, shortcut='osce')
        self.entries = {'manifest.json': json.dumps(dict(categories=['memories', 'templates'], has_api_keys=False)).encode(),
                        'templates/catalog.json': json.dumps([self.template]).encode(),
                        migration.SKILLS + migration.EXAMINER: b'Original instructions',
                        'memories/memory_db/keep.md': b'Unrelated memory',
                        migration.SKILLS + 'custom.md': b'Unrelated Skill',
                        migration.SKILLS + 'skill_meta.json': json.dumps({'custom.md': 'Keep this Skill', next(iter(self.references)): 'Old reference'}).encode()}
        self.entries.update({migration.SKILLS + name: body for name, body in self.references.items()})
        self.write_backup()

    def write_backup(self):
        with ZipFile(self.backup, 'w', ZIP_DEFLATED) as archive:
            for name, body in self.entries.items(): archive.writestr(name, body)

    def test_moves_only_catalogued_references_and_keeps_unrelated_data(self):
        original = hashlib.sha256(self.backup.read_bytes()).hexdigest()
        folder, archive, audit = migration.prepare(self.backup, self.destination)
        self.assertEqual(len(audit), 300)
        self.assertEqual(len(list(folder.rglob('*.md'))), 300)
        for record in audit:
            self.assertEqual((folder / record['path']).read_bytes(), self.references[record['old']])
        self.assertTrue((folder / 'Medicine/Cardiology/Chronic Heart Failure.md').is_file())
        self.assertTrue((folder / 'Surgery/General Surgery/Appendicitis.md').is_file())
        with ZipFile(archive) as z:
            self.assertFalse(any(n.startswith(migration.SKILLS + 'ztf-') for n in z.namelist()))
            self.assertEqual(z.read('memories/memory_db/keep.md'), b'Unrelated memory')
            self.assertEqual(z.read(migration.SKILLS + 'custom.md'), b'Unrelated Skill')
            config = json.loads(z.read(migration.SKILLS + migration.EXAMINER).decode().splitlines()[1])
            templates = json.loads(z.read('templates/catalog.json'))
            self.assertEqual(config['beforeTemplate'], self.template['id'])
            self.assertNotIn('afterTemplate', config)
            self.assertNotIn('nextSkill', config)
            self.assertEqual(len(templates), 1)
            setup = next(t for t in templates if t['id'] == config['beforeTemplate'])
            self.assertEqual(setup['sourceDirectory'], '/mnt/shared/Zero to Finals')
            self.assertFalse(setup.get('shortcut'))
            self.assertFalse(setup.get('linkedSkill'))
            metadata = json.loads(z.read(migration.SKILLS + 'skill_meta.json'))
            self.assertEqual(metadata['custom.md'], 'Keep this Skill')
            self.assertFalse(any(name in metadata for name in self.references))
        self.assertEqual(hashlib.sha256(self.backup.read_bytes()).hexdigest(), original)

    def test_missing_reference_rejects_before_any_write(self):
        del self.entries[migration.SKILLS + next(iter(self.references))]
        self.write_backup()
        with self.assertRaises(ValueError): migration.prepare(self.backup, self.destination)
        self.assertFalse(self.destination.exists())

    def test_existing_different_reference_is_never_overwritten(self):
        file = self.destination / 'Zero to Finals/Medicine/Cardiology/Cardiovascular Disease.md'
        file.parent.mkdir(parents=True)
        file.write_text('My edited reference')
        with self.assertRaises(ValueError): migration.prepare(self.backup, self.destination)
        self.assertEqual(file.read_text(), 'My edited reference')
        self.assertFalse((self.destination / 'zero-to-finals-folder-workflow.agora').exists())

    def test_unrelated_revision_skill_is_preserved_without_configuring_a_chain(self):
        key = migration.SKILLS + 'zero-to-finals-revision.md'
        self.entries[key] = b'My own Skill'
        self.write_backup()
        _, archive, _ = migration.prepare(self.backup, self.destination)
        with ZipFile(archive) as z:
            self.assertEqual(z.read(key), b'My own Skill')
            config = json.loads(z.read(migration.SKILLS + migration.EXAMINER).decode().splitlines()[1])
            self.assertNotIn('nextSkill', config)


if __name__ == '__main__': unittest.main()
