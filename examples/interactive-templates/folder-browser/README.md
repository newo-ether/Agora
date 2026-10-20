# General native folder browser

Import `folder-browser.agora-template.json`, choose any Android folder in its template settings,
and use it as a Skill's Before template. It lists every file type with no extension filter.
Select files and press Start. Browsing is local; only Start enters the normal Skill request.
Existing attachment converters and the chosen model determine which selected files can be processed.
No examiner prompt, topic hierarchy or file-format policy is embedded here.

`sourceFilesAuto=false` avoids scanning the entire tree on open. The interface pages through
`listDirectory` metadata, including empty folders, and supports selecting files across directories.
Filtering or reading configuration is an optional code change, for example:

```javascript
const api = Websandbox.connection.remote;
const config = JSON.parse(await api.readTextFile({path:"menu.json"}));
const raw = await api.readFile({path:"data.custom",encoding:"base64"});
```

These reads stay local and bounded; treat file content as data, never executable code.
