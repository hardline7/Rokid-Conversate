"""Package an already-built APK with our own AI helper and bilingual setup guides.

Run after the Android build and commit. This does not install anything or publish.
"""
import hashlib
import json
import pathlib
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
APK_DIR = ROOT / 'app/build/outputs/apk/debug'
metadata = json.loads((APK_DIR / 'output-metadata.json').read_text(encoding='utf-8'))
element = metadata['elements'][0]
version = element['versionName'].split('-')[0]
tag = 'v' + version
apk_name = f'Rokid-Conversate-{tag}.apk'
zip_name = f'Rokid-Conversate-{tag}-Windows-Setup.zip'
out = ROOT / 'tools/out/releases' / tag
out.mkdir(parents=True, exist_ok=True)
apk_bytes = (APK_DIR / element['outputFile']).read_bytes()
apk_hash = hashlib.sha256(apk_bytes).hexdigest()
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
    raise SystemExit('Commit the release sources before packaging.')
manifest = {
    'version': element['versionName'], 'versionCode': element['versionCode'],
    'tag': tag, 'commit': commit, 'apkName': apk_name, 'apkSha256': apk_hash,
    'signing': 'development/debug', 'helperCliVersion': '0.13.0', 'nodeMinimum': 20,
}
entries = {
    apk_name: apk_bytes,
    'release-manifest.json': (json.dumps(manifest, indent=2) + '\n').encode(),
    'Install-Conversate.ps1': (ROOT / 'tools/distribution/Install-Conversate.ps1').read_bytes(),
    'Install-Helper.ps1': (ROOT / 'tools/rokid-ai/Install-Helper.ps1').read_bytes(),
    'INSTALL.ko.md': (ROOT / 'docs/INSTALL.ko.md').read_bytes(),
    'INSTALL.en.md': (ROOT / 'docs/INSTALL.en.md').read_bytes(),
}
helper_root = ROOT / 'tools/rokid-ai/helper'
for relative in ['AGENTS.md', 'app.js', 'app.json', 'lib/relay.js',
                 'pages/bridge/index.ink', 'pages/cleanup/index.ink', 'widgets/bridge/index.ink']:
    entries['helper/' + relative] = (helper_root / relative).read_bytes()
for name, data in entries.items():
    if name.endswith(('.ps1', '.md')):
        # Windows PowerShell 5.1 needs the BOM to decode the helper's UTF-8 messages.
        if name.endswith('.ps1'):
            data = data.decode('utf-8-sig').encode('utf-8-sig')
        entries[name] = data
(out / apk_name).write_bytes(apk_bytes)
with zipfile.ZipFile(out / zip_name, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for name, data in entries.items():
        archive.writestr(name, data)
with zipfile.ZipFile(out / zip_name) as archive:
    assert archive.testzip() is None
    assert set(archive.namelist()) == set(entries)
    assert hashlib.sha256(archive.read(apk_name)).hexdigest() == apk_hash
checksums = []
for name in [apk_name, zip_name]:
    digest = hashlib.sha256((out / name).read_bytes()).hexdigest()
    checksums.append(f'{digest}  {name}')
(out / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
print(json.dumps({'directory': str(out), 'tag': tag, 'commit': commit,
                  'assets': [apk_name, zip_name, 'SHA256SUMS.txt']}, indent=2))
