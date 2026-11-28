"""Admit a Maven image scan only for a single Tomcat version change."""

import argparse
import re
import subprocess
import sys
from pathlib import Path

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException


MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
TOMCAT_VERSION = re.compile(rb"<tomcat\.version>(11\.0\.([1-9][0-9]{0,5}))</tomcat\.version>")
SOURCE_SHA = re.compile(r"[0-9a-f]{40}")
POM_PATH = b"pom.xml"
MAX_POM_BYTES = 128 * 1024


def git(repository, *args):
    result = subprocess.run(
        ["git", "-C", str(repository), *args],
        check=True, capture_output=True, timeout=15,
    )
    return result.stdout


def tree_entries(repository, revision):
    entries = {}
    for record in git(repository, "ls-tree", "-r", "-z", revision).split(b"\0"):
        if not record:
            continue
        identity, path = record.split(b"\t", maxsplit=1)
        entries[path] = identity
    return entries


def tomcat_property(pom):
    if not 0 < len(pom) <= MAX_POM_BYTES:
        raise ValueError("The POM is outside the supported byte limit.")
    try:
        root = ElementTree.fromstring(
            pom.decode("utf-8"), forbid_dtd=True, forbid_entities=True, forbid_external=True,
        )
    except DefusedXmlException as error:
        raise ValueError("The POM is outside the supported XML input.") from error
    parent = root.find("m:parent", MAVEN_NAMESPACE)
    if (parent is None
            or parent.findtext("m:groupId", namespaces=MAVEN_NAMESPACE) != "org.springframework.boot"
            or parent.findtext("m:artifactId", namespaces=MAVEN_NAMESPACE) != "spring-boot-starter-parent"):
        raise ValueError("The POM must inherit from Spring Boot's starter parent.")
    properties = root.findall("m:properties/m:tomcat.version", MAVEN_NAMESPACE)
    matches = list(TOMCAT_VERSION.finditer(pom))
    if (len(properties) != 1 or len(matches) != 1 or len(properties[0]) != 0
            or properties[0].attrib or properties[0].text != matches[0].group(1).decode("ascii")):
        raise ValueError("The POM must declare one literal Tomcat 11.0 version.")
    return matches[0]


def verify_version_change(baseline, candidate):
    before = tomcat_property(baseline)
    after = tomcat_property(candidate)
    if int(after.group(2)) < 30 or int(after.group(2)) <= int(before.group(2)):
        raise ValueError("The candidate must advance to Tomcat 11.0.30 or later on the 11.0 line.")
    expected = baseline[:before.start(1)] + after.group(1) + baseline[before.end(1):]
    if candidate != expected:
        raise ValueError("The POM changed outside the Tomcat version.")
    return after.group(1).decode("ascii")


def verify_checkouts(base, candidate, base_sha, head_sha):
    if not SOURCE_SHA.fullmatch(base_sha) or not SOURCE_SHA.fullmatch(head_sha) or base_sha == head_sha:
        raise ValueError("The source revisions must be distinct, exact commit SHAs.")
    if any(git(root, "rev-parse", "HEAD").strip().decode("ascii") != sha
           for root, sha in ((base, base_sha), (candidate, head_sha))):
        raise ValueError("The checked-out source revisions do not match the event.")
    if git(candidate, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("The candidate checkout has local changes.")
    base_entries = tree_entries(base, base_sha)
    head_entries = tree_entries(candidate, head_sha)
    changed = {path for path in base_entries.keys() | head_entries.keys()
               if base_entries.get(path) != head_entries.get(path)}
    if changed != {POM_PATH} or any(
        not entries.get(POM_PATH, b"").startswith(b"100644 blob ")
        for entries in (base_entries, head_entries)
    ):
        raise ValueError("The candidate must change only the regular root pom.xml file.")
    baseline_ref = f"{base_sha}:pom.xml"
    proposed_ref = f"{head_sha}:pom.xml"
    if any(not 0 < int(git(root, "cat-file", "-s", ref)) <= MAX_POM_BYTES
           for root, ref in ((base, baseline_ref), (candidate, proposed_ref))):
        raise ValueError("The POM is outside the supported byte limit.")
    baseline = git(base, "show", baseline_ref)
    proposed = git(candidate, "show", proposed_ref)
    candidate_pom = candidate / "pom.xml"
    if (candidate_pom.is_symlink() or candidate_pom.stat().st_size != len(proposed)
            or candidate_pom.read_bytes() != proposed):
        raise ValueError("The candidate POM bytes differ from the verified Git blob.")
    return verify_version_change(baseline, proposed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    args = parser.parse_args()
    try:
        version = verify_checkouts(args.base, args.candidate, args.base_sha, args.head_sha)
    except (ElementTree.ParseError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Tomcat repair source refused: {error}", file=sys.stderr)
        return 1
    print(f"Verified single Tomcat version edit to {version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
