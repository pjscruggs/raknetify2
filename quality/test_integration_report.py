"""Ensure partial and stale compatibility evidence cannot become a public pass."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def planner():
    """Load the same cases and evidence checks used by the verification job."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/integration-plan.py'
    spec = importlib.util.spec_from_file_location('integration_plan', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CompatibilityReportTest(unittest.TestCase):
    """Exercise evidence mismatches that can arise across retries or different builds."""

    def setUp(self):
        """Build a complete set of synthetic passing receipts from the pinned inputs."""
        self.plan = planner()
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)
        self.tier = 'pull_request'
        for entry in self.plan.cases(self.tier):
            folder = self.folder / entry['scenario']
            folder.mkdir()
            row = dict(entry, schema=1, client='26.3', status='passed', revision='expected', raknetify='fixture', sha256='a' * 64,
                       inputs=self.plan.expected_inputs(entry['scenario']))
            (folder / 'result.json').write_text(json.dumps(row))

    def test_missing_and_duplicate(self):
        """Neither another copy nor a missing artifact proves a complete matrix."""
        self.assertEqual(len(self.plan.collect(self.folder, self.tier, 'expected')), 5)
        original = self.folder / 'via-client/result.json'
        copy = self.folder / 'result.json'
        copy.write_bytes(original.read_bytes())
        with self.assertRaisesRegex(AssertionError, 'duplicate'):
            self.plan.collect(self.folder, self.tier, 'expected')
        copy.unlink()
        original.unlink()
        with self.assertRaisesRegex(AssertionError, 'Incomplete'):
            self.plan.collect(self.folder, self.tier, 'expected')

    def test_old_revision_and_changed_dependencies(self):
        """Reject otherwise successful results with stale commits or substituted dependencies."""
        with self.assertRaisesRegex(AssertionError, 'Invalid receipt'):
            self.plan.collect(self.folder, self.tier, 'newer')
        path = self.folder / 'via-plus/result.json'
        row = json.loads(path.read_text())
        row['inputs'] = {}
        path.write_text(json.dumps(row))
        with self.assertRaisesRegex(AssertionError, 'lock mismatch'):
            self.plan.collect(self.folder, self.tier, 'expected')
