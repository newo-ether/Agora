#!/usr/bin/env python3
"""Repackage pinned upstream assets. Requires Python 3 and Node only for this explicit update."""
import hashlib
import io
import pathlib
import re
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/interactive'
REV = 'f6e4388b26a64b9a0714943b08a1ce622b924eec'
BASE = f'https://raw.githubusercontent.com/CopilotKit/OpenIntelligentUI/{REV}/'

def fetch(url):
    return urllib.request.urlopen(url, timeout=60).read()

OUT.mkdir(parents=True, exist_ok=True)
source = fetch(BASE + 'packages/design-system/src/index.ts').decode()
# The pinned design-system module has only constant exports and this one type assertion.
assert source.count(' as const satisfies { imports: Record<string, string> }') == 1
source = source.replace(' as const satisfies { imports: Record<string, string> }', '')
with tempfile.TemporaryDirectory() as directory:
    module = pathlib.Path(directory) / 'design-system.mjs'
    module.write_text(source)
    extractor = "import * as d from './design-system.mjs'; console.log(JSON.stringify({css:d.THEME_CSS+'\\n'+d.SVG_CLASSES_CSS+'\\n'+d.FORM_STYLES_CSS,importmap:d.IMPORTMAP}));"
    runner = pathlib.Path(directory) / 'extract.mjs'
    runner.write_text(extractor)
    result = subprocess.check_output(['node', str(runner)], text=True)
(OUT / 'design-system.js').write_text('// Generated from OpenIntelligentUI ' + REV + '\nwindow.OpenIntelligentDesign = ' + result.strip() + ';\n')
tarball = fetch('https://registry.npmjs.org/@jetbrains/websandbox/-/websandbox-1.2.1.tgz')
assert hashlib.sha256(tarball).hexdigest() == '197254f30500c4875bcd644a5d111e43d0b7391c4d4e051074a5b8817d6a9826'
archive = tarfile.open(fileobj=io.BytesIO(tarball), mode='r:gz')
for member, target in [('package/dist/websandbox.js', 'websandbox.js'), ('package/LICENSE.txt', 'WEBSANDBOX-LICENSE.txt')]:
    data = archive.extractfile(member).read()
    if target.endswith('.js'):
        data = data.replace(b'//# sourceMappingURL=websandbox.js.map', b'')
        # Normalize only Webpack's blank comment banners; do not rewrite executable source.
        data = re.sub(rb'(?m)^(/\*{6}/)[ \t]+$', rb'\1', data)
    (OUT / target).write_bytes(data)
(OUT / 'OPENINTELLIGENTUI-LICENSE.txt').write_bytes(fetch(BASE + 'LICENSE'))
