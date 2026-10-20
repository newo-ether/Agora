# Templates

Templates let you reuse an interactive interface without asking a model to rebuild it.
Open **Settings → Templates** to browse and search saved interfaces, just like Skills.
Select a template to edit its name, short description, source folder or interface code.

## Preview and edit code

Choose **Preview template** to try the current draft in the interactive viewer. Preview does not
add anything to a chat or run a Skill; chat submission buttons are disabled. Folder-backed templates use Android to list reference filenames locally. No model request is made.
If preparation fails, the editor displays the reason.

**Interface code (JSON)** contains the complete interface, including HTML, CSS and JavaScript.
Use **Copy code**, paste it into a chat, and ask for the changes you want. Ask for the complete
revised JSON object, then copy it back using **Paste code**. Paste the object itself without
Markdown code fences. You can also edit the field directly. Preview checks the current draft.

Press **Save** to apply changes. Invalid code cannot be saved. Back discards unsaved edits.
The template keeps its identity, so existing Skills still refer to it. Existing chat cards retain
their original snapshots. Editing here never modifies a Skill's instructions or workflow.

## Native folders and files

Connect any Android folder in Settings → Templates → edit → **Reference folder → Choose folder**.
Android remembers read-only access. No all-files permission, Shell tool, sandbox or model call is
needed. There is no file-type allowlist: templates decide in JavaScript whether to filter names,
extensions or MIME types. OSCE filters Markdown in its own code; the native API does not.

**Build recursive file catalogue when opening** refreshes `window.agoraReferences`
(`path`, `label`, `categories`) and `window.agoraSourceFiles` on each fresh opening. This optional
catalogue is limited to 500 files, 5,000 entries and eight subfolder levels. Turn it off for
on-demand browsing; an empty saved catalogue is valid. Template code can then use:

```javascript
const api = Websandbox.connection.remote;
const page = await api.listDirectory({path: "", offset: 0, limit: 200});
// page: {entries:[{path,name,directory,mimeType,size}], nextOffset:null|number}
const config = JSON.parse(await api.readTextFile({path: "menu.json"}));
const bytes = await api.readFile({path: "sample.custom", encoding: "base64"});
```

Use a directory entry's exact path to browse it, and follow `nextOffset` for another page.
Empty folders and arbitrary file types are supported. A directory can contain up to 5,000 entries;
MIME type and size may be absent when unknown. Directory discovery reads metadata only.
`readTextFile` and `readFile` default to strict UTF-8; Base64 preserves arbitrary bytes. Local
reads are bounded to 128,000 bytes, never execute file contents and make no AI request.

All paths are relative to the connected folder. Absolute paths, parent traversal and URIs are
rejected. Folder grants and provider document IDs stay in Android. Chat operations recheck that
their originating card is still current. Native access is bounded by the user-selected folder,
so an on-demand menu can discover newly added files without reopening. The older
`listReferenceFiles()` bridge returns its immutable catalogue snapshot.

Start submits up to 16 exact paths with `sendPrompt({text:"…",sourceFiles:[...]})`. Android copies
selected files and uses ordinary attachment processing before the request: UTF-8 text becomes a
text attachment, images are normalized and PDFs use the existing page renderer. All files can be
listed; attachment conversion and model support remain separate requirements. Unsupported binary
files never become garbage text. Processing failures stop the request and show an error. Text
attachments are bounded to 128,000 bytes each; native source files share the app's 100 MB combined
limit. Large files remain visible even when too large to read or attach. Start adds no check button
or file-selection dialog.

**Copy for AI** includes the interface, catalogue configuration and general bridge contract,
excluding file bodies, credentials and permission URIs. Return a complete `agora.template-edit/v1`
JSON object and use **Paste code**. Answer-only JSON remains supported. Identity, folder connection
and Skill instructions are preserved. An optional display prefix affects labels only, never paths.

Old Alpine templates retain their Markdown/whitelist contract. Choosing an Android folder switches
to native access. Global tool settings stay unchanged. Reconnect exported templates on another device.

## Manage templates

In an expanded interactive answer, **Save as template** saves the reusable interface.
In Settings, **Import** loads a portable template JSON file. Each saved row's Actions menu offers
Duplicate, Replace from file, Export and Delete. Duplicate creates a new identity; replacement
preserves the selected template's identity and updates only its interface.

Templates collect or display data; Skills provide instructions and own the sequence. Configure
Before/After templates and the next Skill in **Settings → Skills**. Type `/` in chat to browse
Skills. Standalone templates and legacy template shortcuts do not start chat workflows.

## Backup and examples

The **Interactive templates** native export/import and automatic-backup category carries the library.
Merge preserves conflicting IDs and reports conflicts; Replace affects only the selected category.
Archives without templates remain compatible. Skill files use their existing backup category.

The repository includes an unrestricted, paginated folder browser under
`examples/interactive-templates/folder-browser` and a folder-backed OSCE example under `examples/interactive-templates/osce`.
It contains two Skills and two interfaces, with no medical articles or downloads. Point its source
folder at your own Markdown references. It is an example, not a built-in command.
