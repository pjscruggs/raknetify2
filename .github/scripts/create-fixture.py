"""Compile native observers against the same intermediary game and unmodified Create jars the test runs."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace
import zipfile


def sibling(name):
    """Load an adjacent workflow helper by its path."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def java_args(folder, name, arguments):
    """Put the classpath in a JVM argument file, including on Windows."""
    path = folder / (name + '.args')
    path.write_text('\n'.join('"' + str(arg).replace('\\', '/').replace('"', '\\"') + '"' for arg in arguments), encoding='utf-8')
    return '@' + str(path)


def nested(mods, folder):
    """Extract Fabric API and Create's embedded API jars for the observer compiler only."""
    folder.mkdir(exist_ok=True)
    for mod in mods:
        with zipfile.ZipFile(mod) as archive:
            for name in archive.namelist():
                if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                    (folder / Path(name).name).write_bytes(archive.read(name))
    return sorted(folder.glob('*.jar'))


def compile_fixture(root, libs, mods, remapped, java):
    """Build a separate Fabric test mod with no modifications to Create or Raknetify2."""
    classes = root / 'classes'
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, [remapped, *libs, *mods, *nested(mods, root / 'nested')]))
    sources = sorted((Path(__file__).resolve().parents[1] / 'create-fixture').rglob('*.java'))
    javac = Path(java).with_name('javac.exe' if os.name == 'nt' else 'javac')
    subprocess.run([str(javac), java_args(root, 'compile', ['-proc:none', '--release', '17', '-cp', classpath,
                    '-d', classes, *sources])], check=True, timeout=90)
    jar = root / 'create-observer.jar'
    package = 'com.ishland.raknetify.test.create.'
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'train_observer', 'version': '1',
                         'entrypoints': {'main': [package + 'TrainDriver'], 'client': [package + 'TrainObserver']}}))
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes))
    return jar


def prepare(args):
    """Resolve the locked mods and Mojang assets, then obtain the intermediary client from Fabric Loader."""
    address = sibling('client-address-check')
    native = sibling('native-loader-smoke')
    root = args.work_dir
    lock = json.loads((Path(__file__).resolve().parents[1] / 'create-versions.json').read_text())
    manifest = address.fetch_json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    url = next(row['url'] for row in manifest['versions'] if row['id'] == '1.20.1')
    metadata = address.fetch_json(url)
    opts = SimpleNamespace(work_dir=root, jar=args.jar, fixture=args.fixture, baseline=False,
                           ordering_only=True, java_17=args.java)
    folder, libs = address.prepare(opts, '1.20.1', metadata, {'1.20.1': lock})
    remapped = list((folder / '.fabric').rglob('client-intermediary.jar'))
    if not remapped:
        cmd = address.launch_command(opts, '1.20.1', folder, libs)
        with (root / 'remap.log').open('wb') as log:
            subprocess.run([args.java, java_args(folder, 'remap', cmd[1:])], cwd=folder, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        remapped = list((folder / '.fabric').rglob('client-intermediary.jar'))
    mods = [folder / 'mods' / item['filename'] for item in lock]
    witness = compile_fixture(root, libs, mods, remapped[0], args.java)
    native.assets(root / 'assets', metadata)
    return libs, [args.jar, *mods, witness]


def server_folder(args, mods):
    """Install the locked server loader in a disposable flat world, bound only to loopback."""
    scope = SimpleNamespace(work_dir=args.work_dir / 'backend', jar=args.jar,
                            encryption_legacy=None, encryption_modern=None)
    smoke = sibling('server-smoke')
    folder = smoke.prepare_server(scope, '1.20.1', {}, {}, {})
    for jar in mods[1:]:
        shutil.copy2(jar, folder / 'mods' / jar.name)
    (folder / 'fixture-mods.json').write_text(json.dumps(smoke.fixture_mod_inventory(folder)), encoding='utf-8')
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text('server-port=35974\nserver-ip=127.0.0.1\nonline-mode=false\n'
         'enforce-secure-profile=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\n'
         'level-type=minecraft:flat\nlevel-name=train-' + str(os.getpid()) + '\n')
    return folder


def launch_client(args, name, address, inputs, stack):
    """Start a genuine Minecraft client and require its live transport before returning its log."""
    libs, mods = inputs
    process = sibling('legacy-loader-smoke')
    folder = args.work_dir / name
    (folder / 'mods').mkdir(parents=True, exist_ok=True)
    for jar in mods:
        shutil.copy2(jar, folder / 'mods' / jar.name)
    (folder / 'hosts').write_text('127.0.0.1 localhost fixture.test target.fixture.test target.fixture.test.\n::1 localhost\n')
    (folder / 'options.txt').write_text('version:3465\nrenderDistance:3\nsimulationDistance:3\nmaxFps:30\nenableVsync:false\n')
    log = folder / 'client.log'
    arguments = ['-Xmx1750m', '-Ddns.server=' + args.fixture_dns, '-Djdk.net.hosts.file=' + str(folder / 'hosts'), '-cp', os.pathsep.join(map(str, libs)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
                 '--gameDir', folder, '--assetsDir', args.work_dir / 'assets', '--assetIndex', '5', '--version', '1.20.1',
                 '--accessToken', '0', '--username', name, '--quickPlayMultiplayer', address, '--width', '360', '--height', '240']
    child = stack.enter_context(process.running([args.java, java_args(folder, 'client', arguments)], folder, log))
    process.await_line(child, log, 'TRANSPORT ', timeout=180)
    output = log.read_text(encoding='utf-8', errors='replace')
    if address.startswith('raknet;'):
        assert 'TRANSPORT network.ycc.raknet.channel.DatagramChannelProxy' in output, 'Expected a RakNet channel'
    else:
        assert any('TRANSPORT io.netty.channel.' + kind in output for kind in ['socket.nio.NioSocketChannel', 'epoll.EpollSocketChannel']), 'Expected a TCP channel'
    return child, log
