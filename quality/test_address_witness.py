"""Keep native transfer contract evidence distinct from a completed client socket fallback."""
import importlib.util
from pathlib import Path
import unittest


class AddressWitnessTests(unittest.TestCase):
    """Reject missing network evidence without requiring unrelated fixture runners to emit it."""

    def setUp(self):
        """Load the production CI log validators without launching Minecraft or contacting a server."""
        source = Path(__file__).resolve().parents[1] / '.github/scripts/client-address-check.py'
        spec = importlib.util.spec_from_file_location('address_witness', source)
        self.checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.checks)

    def test_transfer_contract_does_not_prove_socket_fallback(self):
        """A successful transfer contract is valid evidence for that check but cannot pass the network gate."""
        text = 'PASS native transfer capability and send overloads'
        for version in ('1.20.5', '1.21.11', '26.3', '26.4-snapshot-7'):
            with self.subTest(version=version):
                self.checks.check_transfer_witness(version, text)
                with self.assertRaises(AssertionError):
                    self.checks.check_address_fallback_witness(version, text)

    def test_completed_fallback_does_not_prove_transfer_contract(self):
        """Require independent evidence for query handling even when the alternate address handshake succeeds."""
        text = 'PASS native multi-address RakNet handshake with unreachable first address'
        self.checks.check_address_fallback_witness('26.3', text)
        with self.assertRaises(AssertionError):
            self.checks.check_transfer_witness('26.3', text)

    def test_versions_without_transfers_have_no_transfer_witness(self):
        """Keep pre-transfer Minecraft coverage valid when those native packet classes do not exist."""
        self.checks.check_transfer_witness('1.20.4', '')
        self.checks.check_address_fallback_witness('1.20.4', '')
