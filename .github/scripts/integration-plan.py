"""Select pinned CI scenarios and publish a summary only for complete matching receipts."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace


def cases(tier):
    """Keep pull requests short and reserve larger version sweeps for release and scheduled builds."""
    selected = [('via-client', '26.2'), ('via-plus', '26.2'), ('mod-payloads', '26.3'), ('paper-plugins', '26.3'), ('proxy-native', '26.3')]
    if tier != 'pull_request':
        selected.extend([('via-client', '1.21.11'), ('via-plus', '1.21.11'), ('terrain', '26.3'), ('proxy', '26.2')])
    if tier in ('schedule', 'workflow_dispatch'):
        selected.append(('reference-pack', '26.3'))
    modes = ['multichannel'] if tier == 'pull_request' else ['multichannel', 'ordered']
    return [{'scenario': name, 'server': version, 'mode': mode}
            for name, version in selected for mode in modes]


def key(row):
    """Identify a unique scenario, protocol pair and negotiated transport policy."""
    return row['scenario'], row['server'], row['mode']


def network(tier):
    """Retain all release shards and use one representative shard per dependency sweep on pull requests."""
    linux = 'ubuntu-24.04'
    suites = ['server', 'ordered', 'proxy', 'bungee', 'proxy-protocol', 'disconnect', 'polymer', 'polymer-krypton', 'packetfixer-krypton', 'packetfixer-polymer-krypton', 'ordering', 'transfers', 'transfer-clients', 'fastipping']
    rows = [{'os': linux, 'suite': suite} for suite in suites]
    shards = [1] if tier == 'pull_request' else range(1, 5)
    rows.extend({'os': linux, 'suite': suite, 'shard': shard}
                for suite in ['voicechat', 'krypton', 'packetfixer'] for shard in shards)
    rows.extend({'os': 'windows-2022', 'suite': suite} for suite in ['disconnect', 'ordering', 'proxy', 'bungee'])
    return rows


def expected_inputs(case):
    """Resolve the same public lock used to install client and server dependencies."""
    spec = importlib.util.spec_from_file_location('integration_smoke', Path(__file__).with_name('integration-smoke.py'))
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    common = smoke.helpers()
    server, client = smoke.inputs(SimpleNamespace(scenario=case), common)
    keys = set(server + client)
    if case == 'terrain':
        keys.update(['terralith-datapack', 'incendium-datapack', 'nullscape-datapack'])
    return common.pinned_inputs(case, keys)


def collect(folder, tier, revision):
    """Reject missing, duplicate, stale or unrecognized results before projecting public fields."""
    expected = {key(row) for row in cases(tier)}
    results = {}
    for path in sorted(folder.rglob('result.json')):
        row = json.loads(path.read_text())
        identity = key(row)
        assert identity in expected and identity not in results, 'Unexpected or duplicate compatibility case'
        assert (row['schema'], row['revision'], row['status'], row['client']) == (1, revision, 'passed', '26.3'), 'Invalid receipt'
        assert row['inputs'] == expected_inputs(row['scenario']), 'Integration lock mismatch'
        results[identity] = row
    assert set(results) == expected, 'Incomplete compatibility results'
    assert len({(row['raknetify'], row['sha256']) for row in results.values()}) == 1, 'Mixed Raknetify2 artifacts'
    return results


def report(args):
    """Write the verified matrix to the build summary with its exact revision and inputs."""
    rows = collect(args.receipts, args.tier, os.environ['GITHUB_SHA'])
    date = datetime.now(timezone.utc).date().isoformat()
    version = next(iter(rows.values()))['raknetify']
    lines = ['## Tested compatibility', '', f'Raknetify2 {version} · {date} UTC', '',
             '| Scenario | Client | Server | Transport |', '| --- | --- | --- | --- |']
    for case, version, mode in sorted(rows):
        lines.append(f'| {case} | 26.3 | {version} | {mode} |')
    sha = os.environ['GITHUB_SHA']
    lines.extend(['', f'[Pinned versions and hashes](https://github.com/pjscruggs/raknetify2/blob/{sha}/.github/integration-inputs.json)'])
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as output:
        output.write('\n'.join(lines) + '\n')


def main():
    """Emit a scheduler matrix or verify the immutable receipts from the same build."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--tier', required=True, choices=['pull_request', 'push', 'workflow_dispatch', 'schedule'])
    parser.add_argument('--receipts', type=Path)
    parser.add_argument('--network', action='store_true')
    args = parser.parse_args()
    if args.receipts:
        report(args)
        return
    rows = network(args.tier) if args.network else cases(args.tier)
    print('matrix=' + json.dumps({'include': rows}, separators=(',', ':')))


if __name__ == '__main__':
    main()
