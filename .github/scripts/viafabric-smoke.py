"""Require a current native client to play through ViaFabric on an older encrypted Fabric server."""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import queue
import subprocess
import threading
from types import SimpleNamespace


def sibling(name):
    """Load an adjacent fixture without adding repository modules to the global import path."""
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(args, lock, native, smoke):
    """Create a loopback world with the exact translation artifacts and synthetic online authentication."""
    settings = SimpleNamespace(work_dir=args.work_dir / 'server-runtime', jar=args.fabric_jar,
                               encryption_legacy=None, encryption_modern=None)
    folder = smoke.prepare_server(settings, lock['server'], {}, {}, {})
    native.configure_server(folder)
    properties = folder / 'server.properties'
    properties.write_text(properties.read_text().replace('online-mode=false', 'online-mode=true')
                          + 'enforce-secure-profile=false\n', encoding='utf-8')
    smoke.install_mods(folder, lock['mods'])
    return folder


def play(args, native, authority, server):
    """Drive dimension changes only after the real client receives each playable world."""
    lock = json.loads((Path(__file__).resolve().parents[2] / 'native-loaders.json').read_text())['neoforge']
    settings = SimpleNamespace(loader='neoforge', java=args.java, work_dir=args.work_dir / 'client-runtime',
                               assets_dir=args.assets_dir, fixture=args.fixture, jar=args.jar,
                               proxy_protocol=False, voicechat=False, encrypted=True,
                               discovery_url=authority.url, world_changes=True, client_port=28990)
    log = settings.work_dir / 'client.log'
    settings.work_dir.mkdir(exist_ok=True)
    log.write_text('', encoding='utf-8')
    cancelled = threading.Event()
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        transitions = pool.submit(sibling('spigot-smoke').change_worlds, server, log,
                                  args.work_dir / 'server.log', cancelled)
        try:
            native.client(settings, lock, native.minecraft_metadata(lock['minecraft']), join=True)
            transitions.result(timeout=5)
        finally:
            cancelled.set()
    assert len(authority.sessions) == 1 and len(authority.accepted) == 1, 'Translated login lost native session agreement'


def exercise(args, lock):
    """Own server lifecycle and require clean runtime, translation, encrypted login and gameplay completion."""
    native = sibling('native-loader-smoke')
    smoke = sibling('server-smoke')
    folder = prepare(args, lock, native, smoke)
    with sibling('session-fixture').SessionAuthority() as authority:
        command = [args.java, '-Xmx1G', '-Dminecraft.api.discovery.host=' + authority.url,
                   '-Dminecraft.api.session.host=' + authority.origin,
                   '-Dminecraft.api.services.host=' + authority.origin,
                   '-Dminecraft.api.profiles.host=' + authority.origin,
                   '-jar', 'fabric-server-launch.jar', 'nogui']
        process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        events = queue.Queue()
        log = args.work_dir / 'server.log'
        reader = threading.Thread(target=native.collect_output, args=(process, log, events))
        reader.start()
        try:
            smoke.await_ready(process, events)
            smoke.check_udp(28990)
            play(args, native, authority, process)
        finally:
            smoke.stop_server(process, reader, timeout=180)
        assert process.returncode == 0, 'Translation server did not shut down cleanly'
        native.check_failures(log)
        text = log.read_text(encoding='utf-8')
        assert 'Could not inject ViaVersion' not in text, 'ViaFabric adapter failed'
        assert 'Raknetify transport fixture joined' in text, 'Translated chat did not reach the server'
    print('PASS ViaFabric encrypted native gameplay', lock['client'], 'to', lock['server'], flush=True)


def main():
    """Resolve exact distribution inputs for the pinned cross-version native regression."""
    parser = argparse.ArgumentParser()
    for name in ('jar', 'fixture', 'fabric-jar', 'work-dir'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--assets-dir', type=Path)
    parser.add_argument('--server-version', default='26.2')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    if args.assets_dir:
        args.assets_dir = args.assets_dir.resolve()
    lock = json.loads((Path(__file__).resolve().parents[1] / 'viafabric-versions.json').read_text())
    lock = {'client': lock['client'], 'server': args.server_version, **lock['servers'][args.server_version]}
    client = json.loads((Path(__file__).resolve().parents[2] / 'native-loaders.json').read_text())['neoforge']
    assert client['minecraft'] == lock['client'], 'Update the pinned translation pair with the native client'
    exercise(args, lock)


if __name__ == '__main__':
    main()
