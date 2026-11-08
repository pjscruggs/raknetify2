"""Publish verified Bukkit artifacts using CurseForge's project-specific game-version catalog."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid


API_ROOT = 'https://dev.bukkit.org'
"""Official Bukkit API origin whose version types distinguish plugins from Minecraft mods."""


def request_json(path, token, payload=None):
    """Perform one authenticated request without retrying uploads whose response may have been lost."""
    headers = {'X-Api-Token': token, 'User-Agent': 'pjscruggs/raknetify2 publication'}
    body = None
    if payload:
        body, content_type = payload
        headers['Content-Type'] = content_type
    request = urllib.request.Request(API_ROOT + path, data=body, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError('CurseForge rejected ' + path + ' with HTTP ' + str(error.code)) from None


def bukkit_versions(games, token):
    """Resolve only Bukkit IDs, rejecting missing or ambiguous matches even when mod IDs share their names."""
    types = request_json('/api/game/version-types', token)
    bukkit = {item['id'] for item in types if item['slug'] == 'bukkit'}
    if not bukkit:
        raise ValueError('CurseForge did not provide a Bukkit version type')
    versions = request_json('/api/game/versions', token)
    identifiers = []
    for game in games:
        matches = [item['id'] for item in versions if item['name'] == game and item['gameVersionTypeID'] in bukkit]
        if len(matches) != 1:
            raise ValueError('Expected one Bukkit game version for ' + game)
        identifiers.append(matches[0])
    return identifiers


def multipart(artifact, metadata):
    """Encode the documented metadata and JAR fields without transforming or rebuilding the artifact bytes."""
    boundary = uuid.uuid4().hex
    metadata_header = '--' + boundary + '\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n'
    file_header = ('\r\n--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="'
                   + artifact.name + '"\r\nContent-Type: application/java-archive\r\n\r\n')
    body = (metadata_header.encode() + json.dumps(metadata).encode('utf-8') + file_header.encode()
            + artifact.read_bytes() + ('\r\n--' + boundary + '--\r\n').encode())
    return body, 'multipart/form-data; boundary=' + boundary


def verified_version(artifacts):
    """Reuse the release gate to reject incomplete, modified or mislabeled bundles before contacting a host."""
    spec = importlib.util.spec_from_file_location('release_plan', Path(__file__).with_name('release-plan.py'))
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    return guard.verify_artifacts(artifacts)


def publish(args):
    """Upload one verified server plugin and persist the returned file ID for reconciliation and download checks."""
    version = verified_version(args.artifacts)
    artifact = args.artifacts / ('raknetify2-spigot-' + version + '.jar')
    if not artifact.is_file():
        raise ValueError('Release does not contain a Spigot artifact')
    game = json.loads((args.source / 'spigot-servers.json').read_text(encoding='utf-8'))['minecraft']
    metadata = {'changelog': args.notes.read_text(encoding='utf-8'), 'changelogType': 'markdown',
                'displayName': 'Raknetify2 ' + version + ' (spigot)',
                'releaseType': 'beta' if '-beta.' in version else 'alpha'}
    if args.dry_run:
        print(json.dumps({'projectId': '1787146', 'file': artifact.name, 'games': [game], 'metadata': metadata}))
        return
    token = os.environ['CURSEFORGE_TOKEN']
    metadata['gameVersions'] = bukkit_versions([game], token)
    result = request_json('/api/projects/1787146/upload-file', token, multipart(artifact, metadata))
    if type(result.get('id')) is not int or result['id'] <= 0:
        raise ValueError('Upload returned no valid file ID. Reconcile before retrying.')
    receipt = {'type': 'curseforge', 'projectId': '1787146', 'fileId': result['id']}
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt) + '\n', encoding='utf-8')
    print(json.dumps(receipt))


def main():
    """Parse explicit publication inputs, keeping credentials exclusively in the process environment."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--dry-run', action='store_true')
    publish(parser.parse_args())


if __name__ == '__main__':
    main()
