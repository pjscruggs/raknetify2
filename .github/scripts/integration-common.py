"""Share pinned inputs and native witnesses across isolated integration scenarios."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def sibling(name):
    """Load a fixture beside this script without changing the global import path."""
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def manifest():
    """Read the exact public integration artifacts and scenario definitions."""
    return json.loads((Path(__file__).resolve().parents[1] / 'integration-inputs.json').read_text())


def install(keys, folder, cache):
    """Verify cached public artifacts before copying them into an owned runtime."""
    folder.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    artifacts = manifest()['artifacts']
    for key in keys:
        entry = artifacts[key]
        algorithm = 'sha512' if 'sha512' in entry else 'sha256'
        path = sibling('native-loader-smoke').download(cache / entry['filename'], entry['url'],
                                                     entry[algorithm], algorithm)
        shutil.copy2(path, folder / entry['filename'])


def clean_log(path):
    """Remove console color escapes without filtering warnings or failures."""
    return re.sub(r'\x1b\[[0-9;]*m', '', path.read_text(encoding='utf-8', errors='replace'))


def pinned_inputs(case, keys):
    """Include the actual proxy distribution alongside the installed mods in proxy receipts."""
    lock = manifest()['artifacts']
    records = {key: lock[key] for key in keys}
    if case.startswith('proxy'):
        key = 'velocity4' if case == 'proxy-native' else 'velocity'
        proxies = json.loads((Path(__file__).resolve().parents[1] / 'proxy-versions.json').read_text())
        records[key] = proxies[key]
    return records


def require(text, markers):
    """Reject a test that loaded a mod without exercising its declared behavior."""
    for marker in markers:
        assert marker in text, 'Missing integration witness: ' + marker


def gameplay(client_log, server_log):
    """Require real gameplay, chat acknowledgement and transport closure."""
    native = sibling('native-loader-smoke')
    native.check_failures(client_log)
    native.check_failures(server_log)
    require(clean_log(client_log), ['PASS native RakNet login, configuration and playable world',
                                   'PASS native transport close completed', 'PASS native final chat sent'])
    require(clean_log(server_log), ['Raknetify transport fixture joined'])


def receipt(args, case, keys):
    """Record only versioned public inputs and a successful scenario, without host paths."""
    with zipfile.ZipFile(args.jar) as jar:
        version = json.loads(jar.read('fabric.mod.json'))['version']
    revision = os.environ.get('GITHUB_SHA') or subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=Path(__file__).resolve().parents[2], text=True).strip()
    result = {'schema': 1, 'scenario': case, 'status': 'passed', 'revision': revision,
              'raknetify': version, 'sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              'mode': args.mode, 'server': args.server_version,
              'client': '26.3', 'inputs': pinned_inputs(case, keys)}
    (args.work_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')


def configure_client(game, args):
    """Explicitly activate translation or mod data exchange in the disposable client."""
    config = game / 'config'
    config.mkdir(exist_ok=True)
    if args.scenario in ('via-client', 'paper-plugins', 'proxy'):
        folder = config / 'ViaFabric'
        folder.mkdir(exist_ok=True)
        protocol = 776 if args.scenario == 'paper-plugins' else {'26.2': 776, '1.21.11': 774}[args.server_version]
        (folder / 'viafabric.yml').write_text('enable-client-side: true\nclient-side-version: ' + str(protocol) + '\n')
    if args.scenario == 'via-plus':
        folder = config / 'viafabricplus'
        folder.mkdir(exist_ok=True)
        (folder / 'settings.json').write_text(json.dumps({'selected_protocol_version': args.server_version}))
    if args.scenario == 'mod-payloads':
        settings = {'Generic': {'hudDataSync': {'enabled': True}},
                    'RendererToggles': {'overlayStructureMainToggle': True}, 'InfoTypeToggles': {'infoServux': True}}
        (config / 'minihud.json').write_text(json.dumps(settings))


def client_command(args, authority, runtime):
    """Launch the real Fabric client with a synthetic login and an explicit gameplay contract."""
    game, libraries, metadata = runtime
    return [args.java, '-Xmx2G', '--enable-native-access=ALL-UNNAMED',
            '-Draknetify.test.join=true', '-Draknetify.test.worldChanges=true',
            '-Draknetify.test.encrypted=' + str(not args.scenario.startswith('proxy')).lower(),
            '-Draknetify.test.dimensions=' + ','.join(args.dimensions),
            '-Draknetify.test.port=' + str(args.client_port), '-Dminecraft.api.discovery.host=' + authority.url,
            '-cp', os.pathsep.join(map(str, libraries)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
            '--gameDir', str(game), '--assetsDir', str(args.assets_dir), '--assetIndex', metadata['assetIndex']['id'],
            '--version', '26.3', '--accessToken', '0', '--username', 'TransportProbe', '--offlineDeveloperMode']


def prepare_client(args, keys):
    """Prepare a fresh client using shared verified downloads and no installed profile."""
    from types import SimpleNamespace
    native = sibling('native-loader-smoke')
    address = sibling('client-address-check')
    root = args.work_dir / 'client-runtime'
    root.mkdir(exist_ok=True)
    settings = SimpleNamespace(work_dir=root, jar=args.jar, fixture=args.fixture, baseline=False,
                               cache_dir=args.cache_dir / 'client-libraries')
    metadata = native.minecraft_metadata('26.3')
    game, libraries = address.prepare(settings, '26.3', metadata, {})
    install(keys, game / 'mods', args.cache_dir)
    configure_client(game, args)
    native.assets(args.assets_dir, metadata)
    return game, libraries, metadata
