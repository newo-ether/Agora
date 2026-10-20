# Folder-backed OSCE menus

The current setup template builds its menu from an Android-selected folder. Import the template,
associate it with your existing OSCE Skill, then open Settings → Templates → Reference folder →
Choose folder. For the installed Zero to Finals workflow, keep its existing Skill and template ID;
connect its existing folder rather than creating another examiner. Its detailed examiner prompt
is unchanged by this feature.

Any folder can be selected, not only a medical library. Subfolders become categories. Filename
extensions are removed from labels, hyphens/underscores become spaces, and exact relative paths
remain untouched. The optional display-prefix setting hides a known prefix without renaming files.
Fresh openings discover new files automatically using Android; no shell tools or AI requests are
needed. Existing open menus retain their snapshots. Start attaches only the chosen reference
before the ordinary Skill request. Copy for AI provides the native catalogue/bridge contract.

The remaining migration material describes older archives and their optional Alpine transport.
Existing archives continue to work; choosing an Android folder replaces that transport for a template.

# Folder-backed OSCE workflow

The Skill owns the clinical conversation; templates collect inputs locally. This example contains
OSCE setup, OSCE review, an examiner Skill and a targeted-revision Skill. It includes no clinical corpus.

1. Put your Markdown references in a folder accessible through Android’s folder picker, for example
   `bmj/Cardiology/Heart failure.md`. Preserve meaningful real filenames.
2. No Alpine sandbox or Shell tools are needed for native folder access.
3. Run `python3 examples/interactive-templates/osce/create_workflow_pack.py /path/to/osce-workflow.agora`
   to package only the Skills and interfaces. Import its Memories/Skills and Templates categories
   using the normal native archive importer. Merge preserves existing conflicts. The folder setup
   has its own template identity so it can coexist with the old Zero to Finals example. If a Skill
   filename already exists, review the reported conflict before choosing which version to keep.
4. In Settings → Templates, edit OSCE setup and choose your Android reference folder.
5. In Settings → Skills, OSCE has Before = OSCE setup, After = OSCE review and Continue with =
   targeted-revision.md. Its shortcut is `osce`; clear an older template's conflicting `/osce`
   shortcut first if one was assigned. No user library items are overwritten automatically.

Invoke `/osce`. Opening lists Markdown paths locally and displays directories as specialties.
Choose Medicine → Cardiology → Heart failure (or the matching directories in your library), select a question count and press Start. Browsing and selection
make no model request. Start validates the source and includes only its content and the chosen
options in the normal Skill request. The examiner asks one question at a time, then calls
complete_skill after the selected exercise is finished. The app shows the review interface;
Continue starts targeted revision, preserving the ordinary conversation and its reference.

The interface has no examiner instructions, Skill names or embedded medical catalogue. Its only
submission is selected input data. Renames need a fresh setup listing; missing/moved/unreadable
files reject Start. Another Skill can reuse these interfaces with different continuation settings.
No live model or physical e-ink acceptance is claimed.

The earlier Zero to Finals download script/catalogue remain legacy preparation/compatibility
resources. They are not the active folder-backed workflow and are not downloaded or executed by
Agora. The new pack generator never downloads or packages reference articles.


## Migrate an existing Zero to Finals article-as-Skills library

Export **Memories** and **Interactive templates** only from the installed app, without credentials.
Keep the export as a recovery backup. Prepare the conversion on your computer:

```sh
python3 examples/interactive-templates/osce/migrate_to_folders.py before.agora migration-output
```

The converter uses the original publisher index to create `Zero to Finals/Medicine/<specialty>`
and `Zero to Finals/Surgery/<specialty>`, naming articles by their condition titles. It preserves
exported Markdown bytes, provenance and any unrelated memories/Skills/templates. Missing references,
filename collisions or conflicting workflow entries stop conversion before writing. It never
redownloads articles. Copy the `Zero to Finals` folder to Android shared storage (`/sdcard`), then
verify its files against `migration-audit.json` before removing any old article Skills.

Import `zero-to-finals-folder-workflow.agora` with **Replace for Memories and Templates** only after
checking that no library edits have been made since the export. Merge would keep the old article
Skills. The reduced archive preserves the existing OSCE setup identity, rewrites
`zero-to-finals-osce.md`, and configures only Before/setup. The examiner offers optional revision
in the same conversation; this Zero to Finals workflow needs no separate revision Skill or After
interface. The generic two-Skill example above still demonstrates chaining for other workflows. Other archive categories are not touched. Export again
and verify the resulting library and copied references. Keep the original backup.

Legacy Alpine archives use `/mnt/shared/Zero to Finals`; Android file managers show `/sdcard/Zero to Finals`. For native access simply choose that Android folder in template settings.
Enable it under **Settings → Shell → Enable Local Sandbox → Manage Local Sandbox**, with Shared
Storage enabled. Installing the F-Droid store app is not required; Agora itself must be the
F-Droid build. A removable card requires its own accessible mount; `/sdcard` is shared storage.

Open a fresh `/osce` setup after migration. Existing chat cards retain their original snapshots.
The new menu groups specialty buttons under Medicine and Surgery headings, matching the original
compact setup. Tap a specialty to view conditions. These groups come directly from the filenames. It includes no
embedded catalogue or reference bodies. `build_pack.py` is obsolete compatibility code and now
requires `--legacy-skills-pack`; it is not the current setup route.
