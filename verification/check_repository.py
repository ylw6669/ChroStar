#!/usr/bin/env python3
"""Static repository integrity checks for ChroStar.

This is not a runtime behavior test and does not replace LSPosed device testing.
"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "module"


def fail(message: str) -> None:
    raise SystemExit(f"repository check failed: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def main() -> None:
    require((ROOT / "README.md").is_file(), "README.md must use canonical capitalization")
    require((ROOT / "AGENTS.md").is_file(), "AGENTS.md is required")
    require((ROOT / ".github/workflows/ci.yml").is_file(), "GitHub Actions workflow is required")

    wrapper_files = [
        MODULE / "gradlew",
        MODULE / "gradlew.bat",
        MODULE / "gradle/wrapper/gradle-wrapper.jar",
        MODULE / "gradle/wrapper/gradle-wrapper.properties",
    ]
    for item in wrapper_files:
        require(item.is_file(), f"missing Gradle Wrapper file: {item.relative_to(ROOT)}")

    properties = (MODULE / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    require("gradle-9.6.1-bin.zip" in properties, "Gradle Wrapper must use 9.6.1 distribution")

    build_gradle = (MODULE / "app/build.gradle").read_text(encoding="utf-8")
    require("versionName \"2.6.7\"" in build_gradle, "versionName must remain 2.6.7")
    require("versionCode 163" in build_gradle, "versionCode must remain 163")

    forbidden_patterns = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in (
            r"\.(?:apk|aab|idsig|zip|7z)$",
            r"\.(?:jks|keystore|p12|pfx)$",
            r"^(?:local\.properties|\.env(?:\..+)?)$",
            r"\.(?:log|tmp|bak)$",
        )
    ]

    tracked_paths = [
        ROOT / line
        for line in __import__("subprocess")
        .check_output(["git", "ls-files"], cwd=ROOT, text=True)
        .splitlines()
    ]
    require(tracked_paths, "repository must contain tracked files")
    for path in tracked_paths:
        normalized = path.relative_to(ROOT).as_posix()
        for pattern in forbidden_patterns:
            require(pattern.search(normalized) is None, f"forbidden tracked file: {normalized}")

    local_properties = MODULE / "local.properties"
    require(
        local_properties not in tracked_paths,
        "module/local.properties must not be tracked by Git",
    )

    portable_path_patterns = (
        re.compile(r"[A-Za-z]:[/\\](?:Users|Windows|Android|agent|AndroidReverseAI)[/\\]", re.IGNORECASE),
        re.compile(r"^[/\\](?:Users|home|root|opt)[/\\]", re.IGNORECASE),
    )
    public_text_files = [
        ROOT / "README.md",
        ROOT / "AGENTS.md",
        ROOT / "TASKS.md",
        ROOT / "CHANGELOG.md",
        ROOT / ".github/workflows/ci.yml",
        *sorted((ROOT / "docs").rglob("*.md")),
    ]
    for path in public_text_files:
        text = path.read_text(encoding="utf-8")
        for pattern in portable_path_patterns:
            for match in pattern.finditer(text):
                fail(f"machine-specific absolute path in {path.relative_to(ROOT)}")

    print("static repository integrity check passed")


if __name__ == "__main__":
    main()
