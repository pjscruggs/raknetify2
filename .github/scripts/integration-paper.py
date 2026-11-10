"""Exercise external packet listeners and translated gameplay on a pinned Paper server."""
import argparse
import importlib.util
import json
from pathlib import Path
import queue
import shutil
import subprocess
import threading
from types import SimpleNamespace


def common_module():
    """Load the shared public integration harness."""
    spec = importlib.util.spec_from_file_location('integration_common', Path(__file__).with_name('integration-common.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(args, common):
    """Install the declared plugin stack and activate observable TAB features."""
    native = common.sibling('native-loader-smoke')
    paper = common.sibling('spigot-smoke')
    settings = SimpleNamespace(work_dir=args.work_dir, platform='paper', jar=args.plugin,
                               proxy_protocol=False, voicechat=False, packetevents=False)
    folder = paper.configure_server(settings, native)
    keys, _ = common.sibling('integration-smoke').inputs(args, common)
    common.install(keys, folder / 'plugins', args.cache_dir)
    shutil.copy2(args.plugin_fixture, folder / 'plugins/packet-witness.jar')
    (folder / 'config/raknetify2-compatibility.json').write_text(json.dumps({'mode': args.mode}))
    tab = folder / 'plugins/TAB'
    tab.mkdir(exist_ok=True)
    (tab / 'config.yml').write_text('header-footer:\n  enabled: true\n  header:\n    - "Transport fixture"\n'
                                  '  footer:\n    - "Packet witness"\nscoreboard:\n  enabled: true\n'
                                  '  scoreboards:\n    fixture:\n      title: "Transport fixture"\n'
                                  '      lines:\n        - "Packet witness"\n')
    lock = json.loads((Path(__file__).resolve().parents[2] / 'spigot-servers.json').read_text())
    return folder, paper.prepare_server(settings, lock, native)


def exercise(args, common):
    """Require encrypted translated gameplay and PacketEvents send, receive and injection witnesses."""
    native = common.sibling('native-loader-smoke')
    smoke = common.sibling('integration-smoke')
    folder, jar = prepare(args, common)
    with common.sibling('session-fixture').SessionAuthority() as authority:
        command = [args.java, '-Xmx2G', '-Dminecraft.api.discovery.host=' + authority.url, '-jar', str(jar), 'nogui']
        server = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        events = queue.Queue()
        reader = threading.Thread(target=native.collect_output, args=(server, args.work_dir / 'server.log', events))
        reader.start()
        try:
            common.sibling('spigot-smoke').await_transport(server, events, args.client_port)
            smoke.play(args, common, authority, server)
        finally:
            common.sibling('server-smoke').stop_server(server, reader, timeout=180)
        assert server.returncode == 0, 'Paper shutdown failed'
    smoke.verify(args, common)
    common.require(common.clean_log(args.work_dir / 'server.log'), [
        '[26.2] (1): [TransportProbe]', 'WITNESS PacketEvents message injected',
        'WITNESS onPacketReceive ', 'WITNESS onPacketSend CHUNK_DATA',
        'WITNESS onPacketSend PLAYER_LIST_HEADER_AND_FOOTER', 'WITNESS onPacketSend UPDATE_SCORE'])
    common.require(common.clean_log(args.work_dir / 'client.log'), ['PacketEvents fixture message'])
    server_keys, client_keys = smoke.inputs(args, common)
    common.receipt(args, args.scenario, server_keys + client_keys)
    print('PASS integration paper-plugins', args.mode, flush=True)


def main():
    """Keep all plugin and client runtimes inside a fresh test directory."""
    parser = argparse.ArgumentParser()
    for name in ['jar', 'fixture', 'plugin', 'plugin-fixture', 'work-dir', 'assets-dir', 'cache-dir']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--mode', choices=['multichannel', 'ordered'], default='multichannel')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    for name in ['jar', 'fixture', 'plugin', 'plugin_fixture', 'work_dir', 'assets_dir', 'cache_dir']:
        setattr(args, name, getattr(args, name).resolve())
    args.work_dir.mkdir(parents=True, exist_ok=False)
    args.scenario = 'paper-plugins'
    args.server_version = '26.3'
    args.client_port = 28990
    args.dimensions = ['minecraft:overworld', 'minecraft:the_nether', 'minecraft:overworld']
    exercise(args, common_module())


if __name__ == '__main__':
    main()
