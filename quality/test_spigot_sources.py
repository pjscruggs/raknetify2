"""Check locked Spigot source preparation and bounded transport failures."""

import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    'spigot_smoke', Path(__file__).resolve().parents[1] / '.github/scripts/spigot-smoke.py')
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class SpigotSourceTests(unittest.TestCase):
    """Exercise a real pinned checkout independently of the remote branch tip."""

    def test_fetches_the_locked_commit_instead_of_the_newer_tip(self):
        """A fresh source checkout must contain the reviewed bytes even after upstream advances."""
        with tempfile.TemporaryDirectory() as temporary:
            remote = Path(temporary) / 'remote'
            remote.mkdir()

            def git(*arguments):
                """Create and inspect only the test-owned source repository."""
                return subprocess.check_output(['git', '-C', str(remote), *arguments], text=True).strip()

            git('init', '--quiet')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'commit.gpgsign', 'false')
            (remote / 'source.txt').write_text('obsolete history\n', encoding='utf-8')
            git('add', 'source.txt')
            git('commit', '--quiet', '-m', 'obsolete ancestor')
            ancestor = git('rev-parse', 'HEAD')
            (remote / 'source.txt').write_text('reviewed\n', encoding='utf-8')
            git('add', 'source.txt')
            git('commit', '--quiet', '-m', 'reviewed source')
            locked = git('rev-parse', 'HEAD')
            (remote / 'source.txt').write_text('new upstream bytes\n', encoding='utf-8')
            git('commit', '--quiet', '-am', 'new upstream source')
            checkout = Path(temporary) / 'checkout'
            with tempfile.TemporaryFile() as output:
                SMOKE.prepare_source(checkout, remote.as_uri(), locked, output)
            self.assertEqual((checkout / 'source.txt').read_text(encoding='utf-8'), 'reviewed\n')
            generated = checkout / 'Generated.java'
            generated.write_text('class Generated {}\n', encoding='utf-8')
            normalized = subprocess.check_output(['git', 'hash-object', '--stdin'], input=b'class Generated {}\n')
            self.assertEqual(subprocess.check_output(
                ['git', '-C', str(checkout), 'hash-object', '--path=Generated.java', str(generated)]), normalized)
            self.assertNotEqual(locked, git('rev-parse', 'HEAD'))
            self.assertEqual(subprocess.check_output(
                ['git', '-C', str(checkout), 'symbolic-ref', 'HEAD'], text=True).strip(), 'refs/heads/master')
            present = subprocess.run(['git', '-C', str(checkout), 'cat-file', '-e', ancestor],
                                     check=False, capture_output=True)
            self.assertNotEqual(present.returncode, 0)

    def test_interrupted_fetch_recovers_without_changing_the_revision(self):
        """A transient timeout must retry the same locked revision and then complete."""
        failure = subprocess.TimeoutExpired('git', 180)
        with patch.object(SMOKE, 'source_git', side_effect=[failure, None]) as run, \
                patch.object(SMOKE.time, 'sleep'):
            SMOKE.fetch_source(Path('fixture'), 'a' * 40, io.StringIO())
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])

    def test_persistent_fetch_failure_stays_blocking(self):
        """Exhausted transfers must fail instead of compiling an unverified source tree."""
        with patch.object(SMOKE, 'source_git', side_effect=subprocess.CalledProcessError(1, 'git')) as run, \
                patch.object(SMOKE.time, 'sleep'), self.assertRaises(subprocess.CalledProcessError):
            SMOKE.fetch_source(Path('fixture'), 'a' * 40, io.StringIO())
        self.assertEqual(run.call_count, 3)
