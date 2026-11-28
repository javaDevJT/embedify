import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "verify_tomcat_repair_source",
    Path(__file__).with_name("verify-tomcat-repair-source.py"),
)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)
POM = b"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <artifactId>test-fixture</artifactId>
  <properties>
    <tomcat.version>11.0.26</tomcat.version>
  </properties>
</project>
"""
CANDIDATE = POM.replace(b"11.0.26", b"11.0.30")


class TomcatVersionTest(unittest.TestCase):
    def test_admits_a_single_compatible_advance(self):
        self.assertEqual(VERIFY.verify_version_change(POM, CANDIDATE), "11.0.30")
        self.assertEqual(
            VERIFY.verify_version_change(CANDIDATE, CANDIDATE.replace(b"11.0.30", b"11.0.31")),
            "11.0.31",
        )

    def test_rejects_an_unfixed_version_downgrade_or_other_series(self):
        for initial, after in (
            (POM, POM.replace(b"11.0.26", b"11.0.29")),
            (CANDIDATE, CANDIDATE),
            (CANDIDATE, POM),
            (POM, POM.replace(b"11.0.26", b"11.1.30")),
        ):
            with self.subTest(after=after):
                with self.assertRaises(ValueError):
                    VERIFY.verify_version_change(initial, after)

    def test_rejects_additional_pom_changes(self):
        with self.assertRaisesRegex(ValueError, "outside the Tomcat version"):
            VERIFY.verify_version_change(POM, CANDIDATE.replace(b"</project>", b"<!-- extra --></project>"))

    def test_rejects_a_duplicate_property_or_wrong_parent(self):
        for after in (
            CANDIDATE.replace(b"</properties>", b"<tomcat.version>11.0.30</tomcat.version></properties>"),
            CANDIDATE.replace(b"spring-boot-starter-parent", b"another-parent"),
        ):
            with self.subTest(after=after):
                with self.assertRaises(ValueError):
                    VERIFY.verify_version_change(POM, after)

    def test_rejects_a_dtd(self):
        with self.assertRaisesRegex(ValueError, "outside the supported XML input"):
            VERIFY.verify_version_change(POM, b"<!DOCTYPE project>" + CANDIDATE)


class CheckoutTest(unittest.TestCase):
    def git(self, root, *args):
        return subprocess.run(
            ["git", "-C", str(root), "-c", "commit.gpgsign=false",
             "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", *args],
            check=True, capture_output=True,
        ).stdout.decode().strip()

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        self.base = root / "base"
        self.head = root / "candidate"
        self.base.mkdir()
        self.git(self.base, "init", "-q")
        (self.base / "pom.xml").write_bytes(POM)
        (self.base / "Dockerfile").write_text("FROM scratch\n")
        self.git(self.base, "add", "--", "pom.xml", "Dockerfile")
        self.git(self.base, "commit", "-qm", "base")
        self.base_sha = self.git(self.base, "rev-parse", "HEAD")
        self.git(root, "clone", "-q", "--no-hardlinks", str(self.base), str(self.head))
        (self.head / "pom.xml").write_bytes(CANDIDATE)
        self.git(self.head, "commit", "-qam", "candidate")
        self.head_sha = self.git(self.head, "rev-parse", "HEAD")

    def verify(self):
        return VERIFY.verify_checkouts(self.base, self.head, self.base_sha, self.head_sha)

    def test_admits_the_exact_checked_out_pair(self):
        self.assertEqual(self.verify(), "11.0.30")

    def test_rejects_a_forged_revision_or_dirty_candidate(self):
        with self.assertRaisesRegex(ValueError, "do not match"):
            VERIFY.verify_checkouts(self.base, self.head, self.base_sha, "a" * 40)
        (self.head / "untracked").write_text("untracked")
        with self.assertRaisesRegex(ValueError, "local changes"):
            self.verify()

    def test_rejects_a_second_changed_file(self):
        (self.head / "Dockerfile").write_text("FROM scratch\n# Different\n")
        self.git(self.head, "commit", "-qam", "different build")
        self.head_sha = self.git(self.head, "rev-parse", "HEAD")
        with self.assertRaisesRegex(ValueError, "only the regular root pom.xml"):
            self.verify()

    def test_rejects_a_pom_symlink(self):
        pom = self.head / "pom.xml"
        pom.unlink()
        pom.symlink_to("Dockerfile")
        self.git(self.head, "add", "--", "pom.xml")
        self.git(self.head, "commit", "-qm", "symlink")
        self.head_sha = self.git(self.head, "rev-parse", "HEAD")
        with self.assertRaisesRegex(ValueError, "only the regular root pom.xml"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
