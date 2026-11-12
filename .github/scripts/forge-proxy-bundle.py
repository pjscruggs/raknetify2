"""Package a tested Forge/FML backend and Velocity setup, including upstream source archives."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request
import zipfile


def bundle(folder):
    """Assemble exact build outputs and digest-pinned upstream releases in installation order."""
    release = json.loads((folder / 'fabric-universal/build/generated/metadata/compatibility.json').read_text())['version']
    endpoint = 'raknetify2-forge-mc1.20.1-' + release + '.jar'
    proxy = 'raknetify2-velocity-' + release + '-all.jar'
    files = {'forge-server/mods/' + endpoint: (folder / 'forge-1.20.1/build/libs' / endpoint).read_bytes(),
             'velocity/plugins/' + proxy: (folder / 'velocity/build/libs' / proxy).read_bytes()}
    config = Path(__file__).resolve().parents[1]
    for path, item in json.loads((config / 'forge-proxy-bundle.json').read_text()).items():
        with urllib.request.urlopen(item['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha512(data).hexdigest() != item['sha512']:
            raise ValueError('Forge proxy component digest mismatch: ' + path)
        files[path] = data
    files['SETUP.txt'] = (config / 'forge-proxy-setup.txt').read_bytes()
    output = folder / ('raknetify2-forge-proxy-mc1.20.1-' + release + '.zip')
    with zipfile.ZipFile(output, 'w') as archive:
        for path, data in sorted(files.items()):
            entry = zipfile.ZipInfo(path)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    return output


if __name__ == '__main__':
    print(bundle(Path(sys.argv[1])))
