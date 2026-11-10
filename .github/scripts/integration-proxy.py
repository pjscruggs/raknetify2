"""Own a loopback Velocity frontend with modern forwarding and an independently supplied client address."""
from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import subprocess


def configure(args, common):
    """Connect the pinned proxy to the disposable Fabric backend with a synthetic forwarding secret."""
    assert args.proxy_jar is not None and args.proxy_jar.is_file(), 'Velocity plugin artifact required'
    folder = args.work_dir / 'proxy'
    folder.mkdir()
    plugins = folder / 'plugins'
    plugins.mkdir()
    shutil.copy2(args.proxy_jar, plugins / 'raknetify2.jar')
    fixture = common.sibling('proxy-smoke')
    lock = json.loads((Path(__file__).resolve().parents[1] / 'proxy-versions.json').read_text())
    entry = 'velocity4' if args.scenario == 'proxy-native' else 'velocity'
    fixture.download(lock[entry], folder / 'velocity.jar')
    fixture.configure(folder, False, False, 28991, proxy_protocol=True)
    config = folder / 'velocity.toml'
    config.write_text(config.read_text().replace('online-mode = true', 'online-mode = false')
                      .replace('force-key-authentication = true', 'force-key-authentication = false')
                      .replace('player-info-forwarding-mode = "NONE"', 'player-info-forwarding-mode = "modern"')
                      .replace('127.0.0.1:1"', '127.0.0.1:28990"'))
    common.sibling('udp-proxy-fixture').configure(folder, True)
    (folder / 'config/raknetify2-compatibility.json').write_text(json.dumps({'mode': args.mode}))
    return folder


@contextmanager
def frontend(args, common):
    """Keep proxy ownership bounded even when the client or backend assertions fail."""
    if not args.scenario.startswith('proxy'):
        yield
        return
    folder = configure(args, common)
    fixture = common.sibling('proxy-smoke')
    log = args.work_dir / 'proxy.log'
    with log.open('w', encoding='utf-8') as output:
        proxy = subprocess.Popen([args.java, '-Xmx512M', '-jar', 'velocity.jar'], cwd=folder,
                                 stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            fixture.ready(proxy, log)
            with common.sibling('udp-proxy-fixture').transport(True, 28991, repeated=True, frontend_port=28992) as port:
                args.client_port = port
                yield
        finally:
            fixture.stop(proxy)
    common.sibling('native-loader-smoke').check_failures(log)
