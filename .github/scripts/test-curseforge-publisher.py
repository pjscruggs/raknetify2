"""Exercise the real Gradle uploader against a loopback API before allowing publication credentials."""
import argparse
from email import policy
from email.parser import BytesParser
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


class UploadFixture(ThreadingHTTPServer):
    """Capture multipart uploads while serving deterministic host version identifiers on loopback only."""

    def __init__(self, games, source):
        """Allocate an ephemeral port and retain only fake authentication and synthetic version metadata."""
        super().__init__(('127.0.0.1', 0), FixtureHandler)
        self.uploads = []
        self.reject_upload = False
        self.source = source
        groups = [('minecraft', games), ('modloader', ['fabric', 'forge', 'neoforge']),
                  ('environment', ['Client', 'Server']), ('java', ['Java 17', 'Java 21', 'Java 25']),
                  ('bukkit', games)]
        self.types = [{'id': index, 'name': name, 'slug': name}
                      for index, (name, _) in enumerate(groups, 1)]
        self.versions = [{'id': index * 1000 + offset, 'gameVersionTypeID': index, 'name': name, 'slug': name}
                         for index, (_, names) in enumerate(groups, 1) for offset, name in enumerate(names)]


class FixtureHandler(BaseHTTPRequestHandler):
    """Implement only the read and upload endpoints required by the production CurseForge client."""

    def log_message(self, format_string, *arguments):
        """Keep fake HTTP traffic out of build logs while retaining requests in the fixture for assertions."""

    def respond(self, status, payload):
        """Send a complete JSON response so the uploader uses its normal success and failure parsers."""
        data = json.dumps(payload).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        """Supply catalog data and reject unknown routes instead of silently accepting a changed API contract."""
        routes = {'/api/game/version-types': self.server.types, '/api/game/versions': self.server.versions}
        self.respond(200 if self.path in routes else 404, routes.get(self.path, {}))

    def do_POST(self):
        """Decode the actual multipart payload and record its bytes before returning success or an uncertain error."""
        prefix = ('Content-Type: ' + self.headers['Content-Type'] + '\r\nMIME-Version: 1.0\r\n\r\n').encode()
        body = self.rfile.read(int(self.headers['Content-Length']))
        message = BytesParser(policy=policy.default).parsebytes(prefix + body)
        parts = {part.get_param('name', header='content-disposition'): part for part in message.iter_parts()}
        self.server.uploads.append({'path': self.path, 'token': self.headers.get('X-Api-Token'),
                                    'metadata': json.loads(parts['metadata'].get_payload(decode=True)),
                                    'file': parts['file'].get_payload(decode=True),
                                    'filename': parts['file'].get_filename()})
        if self.server.reject_upload:
            self.respond(500, {'errorCode': 500, 'errorMessage': 'Synthetic failure after receiving upload'})
        else:
            self.respond(200, {'id': 123456})


def write_init_script(folder, port):
    """Override only the test invocation's endpoint and credentials without making production routing configurable."""
    path = folder / 'fixture.init.gradle'
    path.write_text("""allprojects {
    layout.buildDirectory = new File(gradle.startParameter.projectCacheDir, 'outputs')
    afterEvaluate {
        extensions.getByName('publishMods').curseforge {
            apiEndpoint = 'http://127.0.0.1:PORT'
            accessToken = 'fixture-only-token'
        }
    }
}
""".replace('PORT', str(port)), encoding='utf-8')
    return path


def run_publisher(repository, artifacts, loader, fixture):
    """Execute the production Gradle configuration with real release bytes and no inherited hosting credentials."""
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        init_script = write_init_script(folder, fixture.server_port)
        notes = folder / 'notes.txt'
        notes.write_text('Publication contract fixture.', encoding='utf-8')
        wrapper = repository / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(wrapper), '-p', '.github/publish', 'publishMods', '--no-daemon', '--max-workers=2',
                   '--project-cache-dir', str(folder / 'cache'), '-I', str(init_script),
                   '-PreleaseDirectory=' + str(artifacts), '-PchangelogFile=' + str(notes),
                   '-PnativeManifest=' + str(fixture.source / 'native-loaders.json'),
                   '-PspigotManifest=' + str(fixture.source / 'spigot-servers.json'),
                   '-Ploader=' + loader, '-Pdestination=curseforge', '-PpublishDryRun=false']
        environment = os.environ.copy()
        environment.pop('CURSEFORGE_TOKEN', None)
        environment.pop('MODRINTH_TOKEN', None)
        if loader == 'spigot':
            command = bukkit_command(repository, artifacts, folder, fixture)
            environment['CURSEFORGE_TOKEN'] = 'fixture-only-token'
        return subprocess.run(command, cwd=repository, env=environment, capture_output=True,
                              text=True, timeout=180, check=False)


def bukkit_command(repository, artifacts, folder, fixture):
    """Redirect the real Bukkit uploader inside a temporary harness without adding a production endpoint override."""
    script = repository / '.github/scripts/publish-bukkit.py'
    launcher = folder / 'bukkit_fixture.py'
    launcher.write_text('"""Run the production uploader against a local synthetic API."""\n'
                        'import importlib.util\n'
                        'spec = importlib.util.spec_from_file_location("publisher", ' + repr(str(script)) + ')\n'
                        'publisher = importlib.util.module_from_spec(spec)\n'
                        'spec.loader.exec_module(publisher)\n'
                        'publisher.API_ROOT = "http://127.0.0.1:' + str(fixture.server_port) + '"\n'
                        'publisher.main()\n', encoding='utf-8')
    return [sys.executable, str(launcher), '--artifacts', str(artifacts), '--source', str(fixture.source),
            '--notes', str(folder / 'notes.txt'), '--receipt', str(folder / 'receipt.json')]


def expected_tags(loader, games):
    """Describe supported runtime tags independently of the publisher's Groovy target selection."""
    if loader == 'spigot':
        return set(games)
    if loader == 'fabric':
        return set(games) | {'fabric', 'Java 17', 'Java 21', 'Java 25', 'Client', 'Server'}
    java = {'forge-mc1.20.1': 'Java 17', 'neoforge-mc1.21.1': 'Java 21'}.get(loader, 'Java 25')
    return set(games) | {loader.split('-mc')[0], java, 'Client', 'Server'}


def check_upload(upload, artifacts, loader, fixture):
    """Check project routing, release channel, advertised runtimes and the exact multipart artifact digest."""
    check = unittest.TestCase()
    compatibility = json.loads((artifacts / 'compatibility.json').read_text(encoding='utf-8'))
    version = compatibility['version']
    filename = 'raknetify2-' + loader + '-' + version + '.jar'
    check.assertEqual('/api/projects/' + ('1787146' if loader == 'spigot' else '1785937') + '/upload-file', upload['path'])
    check.assertEqual('fixture-only-token', upload['token'])
    check.assertEqual(filename, upload['filename'])
    check.assertEqual(hashlib.sha256((artifacts / filename).read_bytes()).digest(), hashlib.sha256(upload['file']).digest())
    metadata = upload['metadata']
    check.assertEqual('beta' if '-beta.' in version else 'alpha', metadata['releaseType'])
    check.assertEqual('Publication contract fixture.', metadata['changelog'])
    versions = {item['id']: item['name'] for item in fixture.versions}
    actual = {versions[identifier] for identifier in metadata['gameVersions']}
    games = supported_games(fixture.source, compatibility, loader)
    check.assertEqual(expected_tags(loader, games), actual)
    check.assertEqual(len(actual), len(metadata['gameVersions']))
    if loader == 'spigot':
        bukkit_ids = {item['id'] for item in fixture.versions if item['gameVersionTypeID'] == 5}
        check.assertTrue(set(metadata['gameVersions']).issubset(bukkit_ids), 'Bukkit upload used Minecraft mod IDs')


def exercise_upload(repository, artifacts, loader, fixture):
    """Prove successful upload and a single attempt after server failure using the same production client."""
    check = unittest.TestCase()
    result = run_publisher(repository, artifacts, loader, fixture)
    check.assertEqual(0, result.returncode, result.stdout + result.stderr)
    check.assertEqual(1, len(fixture.uploads))
    check_upload(fixture.uploads[0], artifacts, loader, fixture)
    fixture.uploads.clear()
    fixture.reject_upload = True
    result = run_publisher(repository, artifacts, loader, fixture)
    check.assertNotEqual(0, result.returncode, 'Uploader accepted a server failure')
    check.assertEqual(1, len(fixture.uploads), 'Uncertain upload was replayed or never attempted')
    check_upload(fixture.uploads[0], artifacts, loader, fixture)


def supported_games(source, compatibility, loader):
    """Read the tested source manifests so a future game-version update also changes the contract fixture."""
    if loader == 'fabric':
        return [version.replace('26.4-snapshot-8', '26.4-snapshot') for version in compatibility['gameVersions']]
    if loader == 'spigot':
        return [json.loads((source / 'spigot-servers.json').read_text(encoding='utf-8'))['minecraft']]
    return [json.loads((source / 'native-loaders.json').read_text(encoding='utf-8'))[loader]['minecraft']]


def main():
    """Run a bounded local contract check and always stop its listener before returning to the publication job."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifacts', required=True, type=Path)
    parser.add_argument('--loader', required=True, choices=['fabric', 'forge', 'neoforge', 'forge-mc1.20.1', 'neoforge-mc1.21.1', 'spigot'])
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    artifacts = args.artifacts.resolve()
    compatibility = json.loads((artifacts / 'compatibility.json').read_text(encoding='utf-8'))
    games = supported_games(args.source, compatibility, args.loader)
    with UploadFixture(games, args.source.resolve()) as fixture:
        worker = threading.Thread(target=fixture.serve_forever, daemon=True)
        worker.start()
        try:
            exercise_upload(Path(__file__).resolve().parents[2], artifacts, args.loader, fixture)
        finally:
            fixture.shutdown()
            worker.join(timeout=10)
    print('PASS ' + args.loader + ' upload bytes, metadata, project routing and no retry after uncertain failure')


if __name__ == '__main__':
    main()
