"""Drive real FML clients through Mojang quick play and verify backend chat on the chosen transport."""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import sys
import subprocess
import time
import zipfile


def load_smoke():
    """Reuse the native installer checks, asset verification and official launcher argument expansion."""
    spec = importlib.util.spec_from_file_location('native', Path(__file__).with_name('native-loader-smoke.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def await_line(process, path, marker, timeout=120, offset=0):
    """Wait for a witness from this invocation or report the child that exited early."""
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        text = path.read_bytes()[offset:].decode('utf-8', errors='replace')
        if marker in text:
            return
        if process.poll() is not None:
            raise RuntimeError(f'Exited before {marker}: {path}\n{text[-4000:]}')
        time.sleep(0.25)
    raise TimeoutError(f'Timed out waiting for {marker}: {path}\n{text[-4000:]}')


@contextlib.contextmanager
def running(command, folder, log, shutdown=None):
    """Collect fixture output and close every owned process, including startup failures."""
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            if process.poll() is None:
                if shutdown:
                    process.stdin.write((shutdown + '\n').encode())
                    process.stdin.flush()
                else:
                    process.terminate()
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            process.stdin.close()
            if shutdown and process.returncode != 0:
                raise RuntimeError(f'{log}: process exited {process.returncode}')


def client_args(args, native, folder, lock):
    """Resolve the actual Mojang and loader classpaths for a synthetic multiplayer identity."""
    meta = native.minecraft_metadata(lock['minecraft'])
    assets = args.work_dir / 'assets'
    native.assets(assets, meta)
    with zipfile.ZipFile(args.work_dir / 'installer.jar') as jar:
        profile = json.loads(jar.read('version.json'))
    values = {'classpath': native.client_libraries(folder, meta, profile), 'classpath_separator': os.pathsep,
              'version_name': meta['id'], 'natives_directory': folder / 'natives', 'library_directory': folder / 'libraries',
              'launcher_name': 'TransportTests', 'launcher_version': '1'}
    jvm = native.launcher_arguments(meta['arguments']['jvm'] + profile['arguments']['jvm'], values)
    return ['-Xmx1400m', *jvm, profile['mainClass'], *profile['arguments']['game'], '--gameDir', str(folder),
            '--assetsDir', str(assets), '--assetIndex', meta['assetIndex']['id'], '--version', profile['id'],
            '--accessToken', '0', '--username', 'TransportProbe', '--uuid', '00000000000000000000000000000002',
            '--width', '854', '--height', '480']


def join(args, launch, server, address, name):
    """Prove world entry, a server chat payload and a dimension transition over the selected socket."""
    log = args.work_dir / (name + '-client.log')
    server_log = args.work_dir / 'server.log'
    client = args.work_dir / 'client'
    offset = server_log.stat().st_size
    (client/'options.txt').write_text('onboardAccessibility:false\nsoundCategory_master:0.0\nrenderDistance:3\n')
    arguments = [*launch, '--quickPlayMultiplayer', address]
    argfile = client / 'quickplay.args'
    argfile.write_text('\n'.join('"' + str(arg).replace('\\', '/').replace('"', '\\"') + '"' for arg in arguments))
    with running([args.java, '@' + str(argfile)], client, log) as player:
        await_line(player, server_log, 'TransportProbe joined the game', offset=offset)
        await_line(player, server_log, 'PASS FML play channel round trip', offset=offset)
        server.stdin.write(b'gamemode creative TransportProbe\n')
        server.stdin.write(b'execute as TransportProbe in minecraft:overworld run tp @s 0 100 0\n')
        server.stdin.write(b'execute as TransportProbe in minecraft:the_nether run tp @s 0 100 0\n')
        server.stdin.write(b'execute as TransportProbe at @s if dimension minecraft:the_nether run say FML transport witness\n')
        server.stdin.flush()
        await_line(player, log, 'FML transport witness')
    await_line(server, server_log, 'TransportProbe left the game', offset=offset)
    return log


def proxy_kit(args, native, folder):
    """Install the published kit layout on loopback using a new modern forwarding secret."""
    proxy = args.work_dir / 'velocity'
    plugins = proxy / 'plugins'
    plugins.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.proxy_bundle) as bundle:
        for item in bundle.namelist():
            if item.startswith('velocity/plugins/') and item.endswith('.jar'):
                (plugins / Path(item).name).write_bytes(bundle.read(item))
            if item.startswith('forge-server/mods/proxy-compatible-forge-') and item.endswith('.jar'):
                (folder / 'mods' / Path(item).name).write_bytes(bundle.read(item))
    secret = secrets.token_urlsafe(32)
    (proxy / 'forwarding.secret').write_text(secret)
    config = folder / 'config'
    config.mkdir(exist_ok=True)
    (config / 'proxy-compatible-forge.toml').write_text(
        '[forwarding]\nenabled=true\nmode="MODERN"\nsecret="' + secret + '"\n[crossStitch]\nenabled=true\n')
    (proxy / 'velocity.toml').write_text(
        'config-version="2.9"\nbind="127.0.0.1:28992"\nonline-mode=false\nforce-key-authentication=false\n'
        'player-info-forwarding-mode="MODERN"\nannounce-forge=true\n[servers]\nbackend="127.0.0.1:28990"\n'
        'try=["backend"]\n[forced-hosts]\n"lobby.example.com"=["backend"]\n"factions.example.com"=["backend"]\n'
        '"minigames.example.com"=["backend"]\n')
    lock = json.loads((Path(__file__).resolve().parents[1] / 'proxy-versions.json').read_text())['velocity']
    native.download(proxy/'velocity.jar', lock['url'], lock['sha512'], 'sha512')
    return proxy


def test_proxy(args, launch, server_command, folder):
    """Exercise the same required Forge mod packet through Velocity over TCP and UDP."""
    native = load_smoke()
    proxy = proxy_kit(args, native, folder)
    proxy_log = args.work_dir / 'velocity.log'
    server_log = args.work_dir / 'server.log'
    command = [args.proxy_java, '-Xmx512m', '-Draknetify.velocity.portOverride=28993', '-jar', 'velocity.jar']
    with running(server_command, folder, server_log, 'stop') as server, running(command, proxy, proxy_log, 'shutdown') as velocity:
        await_line(server, server_log, 'Done (')
        await_line(velocity, proxy_log, 'Done (')
        for address, name in [('127.0.0.1:28992', 'proxy-tcp'), ('raknet;127.0.0.1:28993', 'proxy-raknet')]:
            log = join(args, launch, server, address, name)
            native.check_failures(log)
            if 'Unsupported command argument type' in log.read_text(errors='replace'):
                raise AssertionError('Modded commands were lost through the Forge proxy')
        await_line(velocity, proxy_log, 'TransportProbe logged in via RakNet')


def main():
    """Use distinct TCP and UDP ports so successful quick play cannot be a TCP fallback."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--loader', required=True)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--proxy-bundle', type=Path)
    parser.add_argument('--proxy-java', default='java')
    parser.add_argument('--work-dir', required=True, type=Path)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.jar = args.jar.resolve()
    args.voicechat = False
    native = load_smoke()
    lock = json.loads((Path(__file__).resolve().parents[2] / 'native-loaders.json').read_text())[args.loader]
    folder = native.install(args, lock, 'server')
    client = native.install(args, lock, 'client')
    for side in (folder, client):
        shutil.copy2(args.fixture, side / 'mods/transport-test.jar')
    native.configure_server(folder)
    launch = client_args(args, native, client, lock)
    loader = args.loader.split('-mc')[0]
    group = 'minecraftforge' if loader == 'forge' else 'neoforged'
    platform_args = 'win_args.txt' if sys.platform == 'win32' else 'unix_args.txt'
    argfile = folder / 'libraries/net' / group / loader / lock['version'] / platform_args
    server_log = args.work_dir / 'server.log'
    command = [args.java, '-Xmx1200m', '-Draknetify.fabric.portOverride=28991', '@' + str(argfile), 'nogui']
    with running(command, folder, server_log, 'stop') as server:
        await_line(server, server_log, 'Done (')
        for address, name in [('127.0.0.1:28990', 'tcp'), ('raknet;127.0.0.1:28991', 'raknet')]:
            native.check_failures(join(args, launch, server, address, name))
    native.check_failures(server_log)
    if args.proxy_bundle:
        server_log.replace(args.work_dir / 'direct-server.log')
        test_proxy(args, launch, command, folder)


if __name__ == '__main__':
    main()
