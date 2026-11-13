"""Compare two real clients watching the same unmodified Create train and bearing."""
import argparse
import contextlib
import importlib.util
from pathlib import Path
import re
import time


def helper():
    """Load the fixture preparation code shared with the native test driver."""
    spec = importlib.util.spec_from_file_location('create_fixture', Path(__file__).with_name('create-fixture.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def console(server, command):
    """Submit one vanilla server console command."""
    server.stdin.write((command + '\n').encode())
    server.stdin.flush()


def stage_world(server):
    """Build a straight native Create rail and station with room for actual travel."""
    for command in ['forceload add -80 -16 80 16', 'fill -80 79 -3 80 80 12 minecraft:stone',
                    'fill -70 81 4 70 81 4 create:track[shape=xo]',
                    'setblock 1 81 1 create:track_station{TargetDirection:0b,TargetTrack:{X:2,Y:0,Z:3}}',
                    'setworldspawn 0 81 1']:
        console(server, command)


def action(folder, server, process, name):
    """Wait for the server tick that consumes each requested Create operation."""
    pending = folder / 'next-action'
    pending.write_text(name, encoding='utf-8')
    pending.replace(folder / 'train-action')
    process.await_line(server, folder / 'server.log', 'TRAIN ACTION ' + name)


def rows(path):
    """Read the train identity, position, render state and attachment count observed by one client."""
    return re.findall(r'\bTRAIN (\S+) ([\d.eE+-]+) (true|false) (true|false) (\d+)', path.read_text(encoding='utf-8', errors='replace'))


def compare(clients, folder):
    """Reject missing, frozen, detached, derailed or divergent carriages after initial synchronization."""
    authoritative = re.findall(r'SERVER TRAIN (\S+) ([\d.eE+-]+) false', (folder / 'server.log').read_text(encoding='utf-8', errors='replace'))
    assert len(authoritative) == 2 and authoritative[0][0] == authoritative[1][0], authoritative
    identity, stopped = authoritative[-1]
    final = []
    for _, log in clients:
        samples = rows(log)
        first = next(i for i, row in enumerate(samples) if row[2:] == ('true', 'true', '2'))
        assert all(row[0] == identity and row[2:] == ('true', 'true', '2') for row in samples[first:]), samples
        positions = [float(row[1]) for row in samples[first:]]
        assert max(positions) - min(positions) > 1, positions
        assert len(positions) >= 4 and max(positions[-3:]) - min(positions[-3:]) < 0.05, positions
        assert abs(positions[-1] - float(stopped)) < 0.3, (positions[-1], stopped)
        final.append(positions[-1])
    assert abs(final[0] - final[1]) < 0.1, final


def exercise(server, folder, clients, process):
    """Drive assembly, native movement, stopping and bearing assembly with both clients connected."""
    action(folder, server, process, 'stage')
    time.sleep(1)
    action(folder, server, process, 'assemble')
    for child, log in clients:
        process.await_line(child, log, 'true true 2', timeout=30)
    action(folder, server, process, 'move')
    time.sleep(2)
    action(folder, server, process, 'stop')
    console(server, 'place template create:gametest/contraptions/controls 8 81 0')
    time.sleep(3)
    console(server, 'setblock 13 85 4 minecraft:stone_button[face=wall,facing=south,powered=true]')
    bearings = []
    for child, log in clients:
        process.await_line(child, log, 'BEARING ', timeout=30)
        bearings.append(re.findall(r'BEARING (\S+) (\d+)', log.read_text(encoding='utf-8', errors='replace'))[-1])
    assert bearings[0] == bearings[1] and int(bearings[0][1]) == 8, bearings
    compare(clients, folder)


def main():
    """Run unmodified Create with an independent TCP control and a delayed RakNet observer."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.jar = args.jar.resolve()
    args.fixture = args.fixture.resolve()
    fix = helper()
    process = fix.sibling('legacy-loader-smoke')
    libs, mods = fix.prepare(args)
    folder = fix.server_folder(args, mods)
    with contextlib.ExitStack() as stack:
        server = stack.enter_context(process.running([args.java, '-Xmx1750m', '-jar', 'fabric-server-launch.jar', 'nogui'], folder, folder / 'server.log', 'stop'))
        process.await_line(server, folder / 'server.log', 'Done (', timeout=180)
        port, count = stack.enter_context(fix.sibling('create-relay').relay(35974))
        args.fixture_dns = stack.enter_context(fix.sibling('service-dns').running(port))
        stage_world(server)
        clients = []
        for name, address in [('ControlTCP', '127.0.0.1:35974'), ('CheckRakNet', 'raknet;fixture.test')]:
            clients.append(fix.launch_client(args, name, address, (libs, mods), stack))
            console(server, 'tp ' + name + ' 2 82 5')
        exercise(server, folder, clients, process)
        assert count[0] > 5, 'Delay was not exercised'
    print('PASS Create: TCP and RakNet observed the same moving train, stop, attached blocks and bearing.')


if __name__ == '__main__':
    main()
