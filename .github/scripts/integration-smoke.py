"""Require native encrypted gameplay and feature witnesses for pinned integration scenarios."""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import queue
import subprocess
import threading


def helpers():
    """Load shared integration utilities from this checkout."""
    spec = importlib.util.spec_from_file_location('integration_common', Path(__file__).with_name('integration-common.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inputs(args, common):
    """Resolve separate client and server inputs so disabled translation cannot masquerade as coverage."""
    api = ['fabric-api-fabric']
    via = ['viaversion-26.2', 'viafabric-26.2']
    pairs = {
        'via-client': ([], api + via),
        'via-plus': ([], api + ['viafabricplus-26.3']),
        'mod-payloads': (api + ['carpet-fabric', 'servux-fabric', 'no-chat-reports-fabric'],
                         api + ['carpet-fabric', 'minihud-26.3', 'malilib-26.3', 'no-chat-reports-fabric']),
        'terrain': ([], []),
        'reference-pack': (common.manifest()['packs']['server'], common.manifest()['packs']['client']),
        'grim': (api + ['grimac-26.3'], []),
        'proxy-native': (api + ['fabricproxy-lite'], []),
        'proxy': (['fabric-api-26.2', 'fabricproxy-lite'], api + via),
        'paper-plugins': (['viaversion-paper', 'viabackwards-paper', 'packetevents-paper', 'tab-was-taken-paper'], api + via),
    }
    return pairs[args.scenario]


def prepare_server(args, common, keys):
    """Configure only the disposable loopback world and the selected server integrations."""
    from types import SimpleNamespace
    smoke = common.sibling('server-smoke')
    settings = SimpleNamespace(work_dir=args.work_dir / 'server-runtime', jar=args.jar,
                               encryption_legacy=None, encryption_modern=None)
    folder = smoke.prepare_server(settings, args.server_version, {}, {}, {})
    common.sibling('native-loader-smoke').configure_server(folder)
    properties = folder / 'server.properties'
    properties.write_text(properties.read_text().replace('online-mode=false', 'online-mode=true')
                          + 'enforce-secure-profile=false\n', encoding='utf-8')
    config = folder / 'config'
    config.mkdir(exist_ok=True)
    (config / 'raknetify2-compatibility.json').write_text(json.dumps({'mode': args.mode}))
    common.install(keys, folder / 'mods', args.cache_dir)
    if args.scenario.startswith('proxy'):
        (config / 'FabricProxy-Lite.toml').write_text('secret = "isolated-loopback-fixture"\nhackOnlineMode = true\n')
    if args.scenario == 'terrain':
        common.install(['terralith-datapack', 'incendium-datapack', 'nullscape-datapack'],
                       folder / 'transport-test-world/datapacks', args.cache_dir)
        properties.write_text(properties.read_text().replace('level-type=minecraft:flat', 'level-type=minecraft:normal'))
    return folder


def transitions(server, args, common, cancelled):
    """Advance only after each dimension has delivered a playable native chunk."""
    wait = common.sibling('spigot-smoke').wait_marker
    log = args.work_dir / 'client.log'
    for index, destination in enumerate(args.dimensions[1:]):
        wait(log, 'PASS native world boundary ' + str(index) + ' ' + args.dimensions[index], cancelled)
        server.stdin.write('experience set TransportProbe 0 levels\n')
        server.stdin.write('execute as TransportProbe in ' + destination + ' run tp @s 0 120 0\n')
        server.stdin.flush()
    wait(log, 'PASS native final chat sent', cancelled)
    wait(args.work_dir / 'server.log', 'Raknetify transport fixture joined', cancelled)
    server.stdin.write('datapack list enabled\n')
    if args.scenario == 'paper-plugins':
        server.stdin.write('viaversion list\n')
    if args.scenario == 'grim':
        server.stdin.write('grim profile TransportProbe\n')
    server.stdin.write('experience set TransportProbe 42 levels\n')
    server.stdin.flush()


def play(args, common, authority, server):
    """Own a real client and stop the world controller even when startup or gameplay fails."""
    _, keys = inputs(args, common)
    runtime = common.prepare_client(args, keys)
    log = args.work_dir / 'client.log'
    log.write_text('', encoding='utf-8')
    cancelled = threading.Event()
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        controller = pool.submit(transitions, server, args, common, cancelled)
        try:
            with log.open('w', encoding='utf-8') as output:
                subprocess.run(common.client_command(args, authority, runtime), cwd=runtime[0],
                               stdout=output, stderr=subprocess.STDOUT, check=True, timeout=240)
            controller.result(timeout=5)
        finally:
            cancelled.set()
    if not args.scenario.startswith('proxy'):
        assert len(authority.sessions) == 1 and len(authority.accepted) == 1, 'Native authentication digest mismatch'


def verify(args, common):
    """Require feature-specific evidence in addition to connection success."""
    common.gameplay(args.work_dir / 'client.log', args.work_dir / 'server.log')
    client = common.clean_log(args.work_dir / 'client.log')
    server = common.clean_log(args.work_dir / 'server.log')
    if args.scenario.startswith('proxy'):
        common.require(server, ['TransportProbe[/192.0.2.50:'])
        server += common.clean_log(args.work_dir / 'proxy.log')
    if args.mode == 'ordered':
        common.require(server, ['ordered compatibility negotiated'])
    if args.scenario == 'mod-payloads':
        for channel in ['servux:structures', 'servux:hud_metadata', 'carpet:hello']:
            common.require(client, ['WITNESS payload receive ' + channel, 'WITNESS payload send ' + channel])
        common.require(client, ['hudDataChannel: joining Servux'])
    if args.scenario == 'terrain':
        common.require(server, ['file/Terralith_', 'file/Incendium_', 'file/Nullscape_'])
    if args.scenario == 'grim':
        common.require(server, ['Profile for TransportProbe', 'System chat: Version: 26.3', 'Client Brand: fabric'])
    assert 'Could not inject ViaVersion' not in client + server, 'Translation injection failed'


def exercise(args):
    """Bound every owned process and publish a success receipt only after all witnesses pass."""
    common = helpers()
    native = common.sibling('native-loader-smoke')
    smoke = common.sibling('server-smoke')
    server_keys, client_keys = inputs(args, common)
    folder = prepare_server(args, common, server_keys)
    with common.sibling('session-fixture').SessionAuthority() as authority:
        command = [args.java, '-Xmx2G', '-Dminecraft.api.discovery.host=' + authority.url,
                   '-Dminecraft.api.session.host=' + authority.origin, '-Dminecraft.api.services.host=' + authority.origin,
                   '-Dminecraft.api.profiles.host=' + authority.origin, '-jar', 'fabric-server-launch.jar', 'nogui']
        process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        events = queue.Queue()
        reader = threading.Thread(target=native.collect_output, args=(process, args.work_dir / 'server.log', events))
        reader.start()
        try:
            smoke.await_ready(process, events)
            with common.sibling('integration-proxy').frontend(args, common):
                play(args, common, authority, process)
        finally:
            smoke.stop_server(process, reader, timeout=180)
        assert process.returncode == 0, 'Integration server failed shutdown'
    verify(args, common)
    keys = set(server_keys + client_keys)
    if args.scenario == 'terrain':
        keys.update(['terralith-datapack', 'incendium-datapack', 'nullscape-datapack'])
    common.receipt(args, args.scenario, sorted(keys))
    print('PASS integration', args.scenario, args.server_version, args.mode, flush=True)


def main():
    """Resolve explicit artifacts and keep each run in a fresh disposable directory."""
    parser = argparse.ArgumentParser()
    for name in ['jar', 'fixture', 'work-dir', 'assets-dir', 'cache-dir']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--scenario', choices=['via-client', 'via-plus', 'mod-payloads', 'terrain', 'reference-pack', 'grim', 'proxy', 'proxy-native'], required=True)
    parser.add_argument('--proxy-jar', type=Path)
    parser.add_argument('--server-version', default='26.3')
    parser.add_argument('--mode', choices=['multichannel', 'ordered'], default='multichannel')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    for name in ['jar', 'fixture', 'work_dir', 'assets_dir', 'cache_dir']:
        setattr(args, name, getattr(args, name).resolve())
    args.work_dir.mkdir(parents=True, exist_ok=False)
    args.client_port = 28990
    args.dimensions = ['minecraft:overworld', 'minecraft:the_nether']
    if args.scenario == 'terrain':
        args.dimensions.append('minecraft:the_end')
    args.dimensions.append('minecraft:overworld')
    exercise(args)


if __name__ == '__main__':
    main()
