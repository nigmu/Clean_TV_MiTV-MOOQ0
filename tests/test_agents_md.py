"""Keeps AGENTS.md honest: every path it names exists, and its numbers match the files they come from.

Coding agents follow AGENTS.md to the letter, so a renamed file or a moved gate turns into a confident
wrong instruction. A path is anything in a link or in backticks that has a slash or a file extension;
templates such as `examples/<brand>-<model>/` and globs are skipped, and so are the local folders
.gitignore names (`backup/`, `tools/venv`), which a fresh clone does not have yet. A bare file name
such as `config.json` counts if a file of that name exists somewhere in the repo.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
AGENTS = ROOT / "AGENTS.md"
GRADLE = ROOT / "launcher" / "app" / "build.gradle"

EXTENSION = re.compile(r"\.(md|py|sh|ps1|yml|yaml|gradle|xml|json|txt|jpg|png|java)$")
TEMPLATE = re.compile(r"[<>*{}$=%]")
LINK = re.compile(r"\]\(([^)\s]+)\)")
CODE = re.compile(r"(`+)(.+?)\1")
SKIP_DIRS = {".git", "venv", "build", ".gradle", "__pycache__"}


def text():
    return AGENTS.read_text(encoding="utf-8")


def ignored_prefixes():
    """Folders .gitignore names: they exist on a contributor's machine, not in a clone.

    A trailing slash (`shots/`) and a contents pattern (`shots/*`, `shots/**`) both mean the
    folder is local. Negation lines (`!shots/before-after-60s-white.mp4`) are the exception and are not prefixes.
    """
    lines = (ROOT / ".gitignore").read_text(encoding="utf-8").split("\n")
    prefixes = []
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if line.endswith("/"):
            prefixes.append(line.rstrip("/"))
        elif line.endswith("/*") or line.endswith("/**"):
            prefixes.append(line.split("*", 1)[0].rstrip("/"))
    return prefixes


def file_names():
    names = set()
    for path in ROOT.rglob("*"):
        if not SKIP_DIRS.intersection(path.relative_to(ROOT).parts):
            names.add(path.name)
    return names


def named_paths():
    """(source, path) for every link target outside code and every path-like word in backticks."""
    body = text()
    found = []
    for target in LINK.findall(CODE.sub("", body)):
        if not re.match(r"(https?:|mailto:|#)", target):
            found.append(("link", target.split("#")[0]))
    for _, span in CODE.findall(body):
        for word in span.split():
            word = word.strip("'\"(),;:")
            if word.startswith("./"):
                word = word[2:]
            if not word or TEMPLATE.search(word) or word.startswith(("~", "/", "-", "http")):
                continue
            if "/" in word or EXTENSION.search(word):
                found.append(("code", word))
    return found


class AgentsPathsTest(unittest.TestCase):
    def test_every_named_path_exists(self):
        ignored = ignored_prefixes()
        names = file_names()
        for source, path in named_paths():
            with self.subTest(path=path, source=source):
                if any(path == p or path.startswith(p + "/") for p in ignored):
                    continue
                if "/" not in path.rstrip("/"):
                    self.assertTrue((ROOT / path).exists() or path.rstrip("/") in names,
                                    f"AGENTS.md names {path}, and no file in the repo has that name")
                else:
                    self.assertTrue((ROOT / path).exists(), f"AGENTS.md names {path}, which does not exist")

    def test_it_names_paths_at_all(self):
        # A parser that silently finds nothing would make the test above pass for any text.
        paths = [p for _, p in named_paths()]
        for expected in ("PROMPT.md", "tools/disable.sh", "docs/TROUBLESHOOTING.md", "examples/README.md"):
            self.assertIn(expected, paths)


class AgentsNumbersTest(unittest.TestCase):
    def test_min_sdk_matches_build_gradle(self):
        min_sdk = re.search(r"^\s*minSdk\s+(\d+)", GRADLE.read_text(encoding="utf-8"), re.M).group(1)
        stated = re.findall(r"minSdk`?\D{0,6}(\d+)", text())
        self.assertTrue(stated, "AGENTS.md no longer states minSdk")
        for value in stated:
            self.assertEqual(value, min_sdk, "AGENTS.md and launcher/app/build.gradle disagree on minSdk")


class AgentsEntryPointsTest(unittest.TestCase):
    def test_claude_and_gemini_import_agents_md(self):
        for name in ("CLAUDE.md", "GEMINI.md"):
            with self.subTest(file=name):
                lines = (ROOT / name).read_text(encoding="utf-8").split("\n")
                self.assertIn("@AGENTS.md", [l.strip() for l in lines], f"{name} must import AGENTS.md")

    def test_people_docs_point_at_it(self):
        for name in ("README.md",):
            with self.subTest(file=name):
                self.assertIn("](AGENTS.md)", (ROOT / name).read_text(encoding="utf-8"))

    def test_cleaning_a_tv_comes_first(self):
        body = text()
        self.assertLess(body.index("PROMPT.md"), body.index("## Repo map"),
                        "an agent cleaning a TV must be sent to PROMPT.md before the contributor sections")


if __name__ == "__main__":
    unittest.main()
