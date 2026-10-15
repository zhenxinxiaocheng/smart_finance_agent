import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else "/bin/bash"

class BootstrapTests(unittest.TestCase):
    def run_prepare(self, project):
        self.assertTrue((ROOT / "runtime/start.sh").exists(), "bootstrap has not been implemented")
        return subprocess.run([BASH, (ROOT / "runtime/start.sh").as_posix(), "prepare", project.as_posix()],
                              text=True, capture_output=True, timeout=20)

    def test_missing_configuration_cannot_initialize_a_deployment(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "tests") as directory:
            project = Path(directory)
            result = self.run_prepare(project)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((project / ".data/deploy/.env").exists())

    def test_prepare_preserves_uploaded_configuration_and_existing_database_credentials(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "tests") as directory:
            project = Path(directory)
            private = project / ".data/deploy"
            private.mkdir(parents=True)
            config = private / "application-local.yml"
            original = b"jwt:\n  secret: private-value\n"
            config.write_bytes(original)
            first = self.run_prepare(project)
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            environment = (private / ".env").read_bytes()
            second = self.run_prepare(project)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertEqual((private / ".env").read_bytes(), environment)
            self.assertEqual(config.read_bytes(), original)
            self.assertNotIn("private-value", first.stdout + first.stderr)

if __name__ == "__main__":
    unittest.main()
