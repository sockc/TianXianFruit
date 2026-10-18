"""Pure transformation tests: no Docker daemon or databases required."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import ipaddress

SOURCE = Path(__file__).resolve().parents[1] / "tianxian_repair.py"
spec = importlib.util.spec_from_file_location("tianxian_repair", SOURCE)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)

SERVER = """services:
  postgres:
    image: postgres:16
  backend:
    ports:
      - "127.0.0.1:18080:8000"
      - "192.168.6.157:18080:8000"
    networks:
      - internal
      - txb_web_api
  backup:
    image: postgres:16
networks:
  internal:
    internal: true
  txb_web_api:
    external: true
    name: tianxian-recovery-test
"""

WEB = """services:
  tianxian-web:
    ports:
      - "127.0.0.1:3800:3000"
      - "192.168.6.157:3800:3000"
    networks:
      - txb_web_api
networks:
  txb_web_api:
    external: true
    name: tianxian-recovery-test
"""


class RepairTests(unittest.TestCase):
    def test_update_old_ip_from_same_lan(self):
        assigned = [ipaddress.ip_interface("127.0.0.1/8"),
                    ipaddress.ip_interface("192.168.6.203/24")]
        self.assertEqual(repair.choose_binds(["127.0.0.1", "192.168.6.157"], assigned),
                         ["127.0.0.1", "192.168.6.203"])

    def test_refuse_unmatched_ip(self):
        assigned = [ipaddress.ip_interface("127.0.0.1/8")]
        with self.assertRaises(ValueError):
            repair.choose_binds(["192.168.6.157"], assigned)

    def test_no_wildcard_combination(self):
        assigned = [ipaddress.ip_interface("127.0.0.1/8")]
        with self.assertRaises(ValueError):
            repair.choose_binds(["127.0.0.1"], assigned, "0.0.0.0,127.0.0.1")

    def test_port_patch_preserves_postgres_and_other_services(self):
        patched = repair.patch_ports(SERVER, "backend", 8000, 18080,
                                     ["127.0.0.1", "192.168.6.203"])
        self.assertIn("192.168.6.203:18080:8000", patched)
        self.assertNotIn("192.168.6.157", patched)
        self.assertIn("  postgres:\n    image: postgres:16", patched)
        self.assertEqual(repair.patch_ports(patched, "backend", 8000, 18080,
                                            ["127.0.0.1", "192.168.6.203"]), patched)

    def test_web_ports(self):
        patched = repair.patch_ports(WEB, "tianxian-web", 3000, 3800,
                                     ["127.0.0.1", "192.168.6.203"])
        self.assertIn("192.168.6.203:3800:3000", patched)

    def test_backend_alias_is_persistent_and_idempotent(self):
        new = repair.patch_backend_alias(SERVER, "txb_web_api")
        self.assertIn("      internal: {}", new)
        self.assertIn("      txb_web_api:\n        aliases:\n          - backend", new)
        self.assertEqual(repair.patch_backend_alias(new, "txb_web_api"), new)

    def test_shared_network(self):
        a = {"services": {"backend": {"networks": {"internal": None, "txb_web_api": None}}},
             "networks": {"internal": {"name": "server_internal"},
                          "txb_web_api": {"name": "tianxian-recovery-test"}}}
        b = {"services": {"tianxian-web": {"networks": {"txb_web_api": None}}},
             "networks": {"txb_web_api": {"name": "tianxian-recovery-test"}}}
        self.assertEqual(repair.shared_network(a, b), ("txb_web_api", "tianxian-recovery-test"))

    def test_manager_config_only_updates_four_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.env"
            path.write_text("BACKEND_PORT='18080'\nWEB_PORT='3800'\nBACKUP_KEY='/secure/key'\n")
            repair.update_manager_config(path, 18081, ["127.0.0.1"], 3801, ["127.0.0.1"])
            self.assertIn("BACKEND_PORT=18081", path.read_text())
            self.assertIn("WEB_PORT=3801", path.read_text())
            self.assertIn("BACKUP_KEY='/secure/key'", path.read_text())


if __name__ == "__main__":
    unittest.main()
