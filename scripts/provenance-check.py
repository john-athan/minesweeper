#!/usr/bin/env python3
"""Provenance gate: does code in this repo also live in someone else's repo?

The worry this answers is narrow and real: an assistant reproduces a chunk of
somebody's source from memory, it lands here, and the first time anyone notices
is a letter. So before a release we take the most distinctive lines that are new
since the last tag, ask GitHub code search whether those exact lines exist
elsewhere, and look at the license of whatever comes back.

A hit under a copyleft license in a permissive repo is the case that actually
costs money, so that is the only thing that fails the build. Everything else is
reported and left to a human, because convergent output is the common case:
two projects that ask a model for RFC 4648 base64 get near-identical code, and
that is neither copying nor infringement.

Needs `gh` (authenticated) and git. No other dependencies.

    ./scripts/provenance-check.py                 # changes since the last tag
    ./scripts/provenance-check.py --since HEAD~20
    ./scripts/provenance-check.py --all           # every tracked source file
    ./scripts/provenance-check.py --report-only   # never exit non-zero
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# Licenses that cannot be absorbed into a permissive project without
# consequences. A hit here is the one thing that stops a release.
COPYLEFT = {
    "GPL-1.0", "GPL-2.0", "GPL-3.0", "AGPL-1.0", "AGPL-3.0",
    "LGPL-2.0", "LGPL-2.1", "LGPL-3.0", "MPL-1.1", "MPL-2.0",
    "EPL-1.0", "EPL-2.0", "CDDL-1.0", "CDDL-1.1", "OSL-3.0",
    "SSPL-1.0", "EUPL-1.1", "EUPL-1.2",
}

SOURCE_SUFFIXES = {
    ".rs", ".py", ".ts", ".tsx", ".js", ".jsx", ".swift", ".kt", ".java",
    ".go", ".c", ".h", ".cc", ".cpp", ".hpp", ".rb", ".sh", ".css", ".scss",
}

# Paths whose contents are not ours to be judged on, or are generated.
SKIP_PATH = re.compile(
    r"(^|/)(node_modules|vendor|third_party|\.venv|venv|target|dist|build|"
    r"generated|__pycache__|\.git)/"
)

COMMENT_START = ("//", "#", "/*", "*", "--", '"""', "'''", "<!--", ";")

# Lines that are structure rather than substance. Searching for an import or a
# closing brace tells you nothing except that the language is popular.
BOILERPLATE = re.compile(
    r"^\s*(import|from|use|include|require|package|export|public|private|"
    r"return|const|let|var|def|fn|func|class|struct|interface|impl|if|else|"
    r"for|while|try|catch|@|\}|\)|\]|<|\{)?\s*[\w\.\*\{\}, ]*;?\s*$"
)


def sh(args: list[str], check: bool = True) -> str:
    r = subprocess.run(args, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"command failed: {' '.join(args)}\n{r.stderr.strip()}")
    return r.stdout


def repo_owner() -> str:
    url = sh(["git", "remote", "get-url", "origin"], check=False).strip()
    m = re.search(r"[:/]([^/:]+)/[^/]+?(\.git)?$", url)
    return m.group(1).lower() if m else ""


def own_license() -> str:
    for name in ("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING"):
        p = Path(name)
        if not p.exists():
            continue
        head = p.read_text(errors="replace")[:400].upper()
        if "GNU AFFERO" in head:
            return "AGPL-3.0"
        if "GNU GENERAL PUBLIC" in head:
            return "GPL-3.0"
        if "APACHE" in head:
            return "Apache-2.0"
        if "MIT" in head:
            return "MIT"
        if "MOZILLA" in head:
            return "MPL-2.0"
    return "UNKNOWN"


def baseline(since: str | None, scan_all: bool) -> str | None:
    """The ref to diff against, or None to scan every tracked file."""
    if scan_all:
        return None
    if since:
        return since
    tag = sh(["git", "describe", "--tags", "--abbrev=0"], check=False).strip()
    if tag:
        return tag
    # No tags yet: everything in the repo is new.
    return None


def project_words() -> set[str]:
    """Tokens that identify this project, so we do not search for our own name."""
    words = set()
    name = Path.cwd().name.lower()
    words.update(re.split(r"[-_]", name))
    owner = repo_owner()
    if owner:
        words.update(re.split(r"[-_]", owner))
    for manifest in ("Cargo.toml", "package.json", "pyproject.toml"):
        p = Path(manifest)
        if p.exists():
            for m in re.finditer(r'name\s*[:=]\s*"([^"]+)"', p.read_text(errors="replace")):
                words.update(re.split(r"[-_/@]", m.group(1).lower()))
    return {w for w in words if len(w) > 3}


# Declarations only. `let`/`var`/`const` are deliberately absent: they bind local
# names like `shift` and `line`, and treating those as ours would mark every
# ordinary line as unmatchable, which is the opposite of what we want to find.
DEFINITION = re.compile(
    r"\b(?:fn|def|class|struct|enum|trait|interface|type|func|function)\s+"
    r"([A-Za-z_][A-Za-z0-9_]{2,})"
)


def project_symbols() -> set[str]:
    """Every name this repo defines for itself.

    A line built out of our own symbols cannot match anyone else's repo, so
    probing it burns a query for nothing. The lines worth asking about are the
    opposite: written entirely in language and standard-library vocabulary, which
    is exactly what a model reproduces from memory when it has seen the same
    well-known routine in a thousand repos.
    """
    syms = set()
    for path in sh(["git", "ls-files"]).splitlines():
        if Path(path).suffix not in SOURCE_SUFFIXES or SKIP_PATH.search(path):
            continue
        try:
            text = Path(path).read_text(errors="replace")
        except OSError:
            continue
        syms.update(m.group(1) for m in DEFINITION.finditer(text))
    return syms


# Bit twiddling, masks, shifts and hex constants are the fingerprint of the code
# most likely to be reproduced verbatim: hashes, encoders, parsers, checksums.
# Bare multi-digit numbers are excluded on purpose, or every test assertion and
# every drawing coordinate outranks the real thing.
ALGORITHMIC = re.compile(r"(<<|>>|0x[0-9a-fA-F]{2,}|&\s*0x|&\s*\d|\^|~\s*\w|%\s*\d)")

# Our own tests and one-off asset generators are not what a letter would be about.
# This file is excluded too: probing its own docstring burns the whole budget on
# English sentences, which is exactly what the first CI run did.
NOT_PRODUCT = re.compile(
    r"(^|/)(tests?|benches|examples|assets|fixtures|__tests__)/|"
    r"(^|/)(test_[^/]+|[^/]+_test|[^/]+\.test|conftest)\.[a-z]+$|"
    r"(^|/)provenance-check\.py$"
)

# Punctuation that code has and prose does not.
CODEY = re.compile(r"[(){}\[\];=<>+\-*/%&|^!~]")


def candidate_lines(base: str | None, limit: int) -> list[tuple[str, str]]:
    """The most distinctive (path, line) pairs worth asking GitHub about."""
    if base:
        raw = sh(["git", "diff", "-U0", f"{base}...HEAD"], check=False)
        if not raw.strip():
            raw = sh(["git", "diff", "-U0", base], check=False)
        lines, path = [], ""
        for ln in raw.splitlines():
            if ln.startswith("+++ b/"):
                path = ln[6:]
            elif ln.startswith("+") and not ln.startswith("+++"):
                lines.append((path, ln[1:]))
    else:
        lines = []
        for path in sh(["git", "ls-files"]).splitlines():
            if Path(path).suffix not in SOURCE_SUFFIXES or SKIP_PATH.search(path):
                continue
            try:
                for ln in Path(path).read_text(errors="replace").splitlines():
                    lines.append((path, ln))
            except OSError:
                continue

    mine, symbols = project_words(), project_symbols()
    seen, scored = set(), []
    for path, line in lines:
        if Path(path).suffix not in SOURCE_SUFFIXES or SKIP_PATH.search(path):
            continue
        if NOT_PRODUCT.search(path):
            continue
        # Drop a trailing comment before anything else. It is our prose, it would
        # never match another repo, and left attached it makes a perfectly good
        # code line read as English to the filter below. The `\s+` guard is what
        # keeps `https://` intact.
        s = re.sub(r"\s+(//|#)\s.*$", "", line.strip()).rstrip(",;")
        if s.startswith(("assert", "expect(", "self.assert")):
            continue
        # An import list is a fact about the ecosystem, not about this repo.
        if s.startswith(("use ", "import ", "from ", "require(", "#include", "@import")):
            continue
        if not (45 <= len(s) <= 130):
            continue
        if s.startswith(COMMENT_START) or BOILERPLATE.match(s):
            continue
        # A docstring body does not start with a comment marker, so it reaches
        # here looking like source. An English sentence is many words and almost
        # no operators; code is the other way round.
        if len(re.findall(r"[A-Za-z']{2,}", s)) >= 7 and len(CODEY.findall(s)) < 5:
            continue
        if '"' in s or "'" in s:
            # Quoted text is either user-facing copy (ours by definition) or it
            # breaks the phrase query. Either way it is a poor probe.
            continue
        low = s.lower()
        if any(w in low for w in mine):
            continue
        if s in seen:
            continue
        seen.add(s)
        toks = re.findall(r"[A-Za-z_][A-Za-z0-9_]{3,}", s)
        if len(toks) < 2:
            continue
        # A probe is worth a query when it is dense with the machinery that gets
        # memorised (shifts, masks, hex) and thin on names only we use. Lines
        # made of our own symbols score negative and drop out entirely.
        ours = sum(1 for t in toks if t in symbols)
        generic = len({t for t in toks if t not in symbols})
        algo = len(ALGORITHMIC.findall(s))
        score = 5 * algo + 2 * generic - 6 * ours
        if score <= 0:
            continue
        scored.append((score, path, s))

    scored.sort(key=lambda x: -x[0])
    return [(p, s) for _, p, s in scored[:limit]]


def search(phrase: str) -> list[dict]:
    r = subprocess.run(
        ["gh", "search", "code", f'"{phrase}"', "--limit", "10",
         "--json", "repository,path"],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        err = r.stderr.strip()
        if "rate limit" in err.lower():
            print("  ! GitHub code search rate limit reached, pausing 60s", file=sys.stderr)
            time.sleep(60)
            return search(phrase)
        return []
    try:
        return json.loads(r.stdout or "[]")
    except json.JSONDecodeError:
        return []


_license_cache: dict[str, str] = {}


def license_of(repo: str) -> str:
    if repo not in _license_cache:
        out = sh(["gh", "api", f"repos/{repo}", "--jq", ".license.spdx_id"], check=False)
        spdx = out.strip()
        _license_cache[repo] = spdx if spdx and spdx != "null" else "NONE"
    return _license_cache[repo]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--since", help="git ref to diff against (default: last tag)")
    ap.add_argument("--all", action="store_true", help="scan every tracked source file")
    ap.add_argument("--limit", type=int, default=12, help="how many lines to probe")
    ap.add_argument("--report-only", action="store_true", help="never exit non-zero")
    args = ap.parse_args()

    base = baseline(args.since, args.all)
    ours, owner = own_license(), repo_owner()
    probes = candidate_lines(base, args.limit)

    scope = "all tracked source" if base is None else f"changes since {base}"
    print(f"provenance-check: {scope}, license {ours}, {len(probes)} probes\n")
    if not probes:
        print("nothing distinctive enough to probe. clean.")
        return 0

    findings = []
    for i, (path, line) in enumerate(probes, 1):
        print(f"[{i}/{len(probes)}] {path}: {line[:70]}")
        for hit in search(line):
            repo = hit["repository"]["nameWithOwner"]
            if repo.split("/")[0].lower() == owner:
                continue
            lic = license_of(repo)
            findings.append((repo, hit["path"], lic, path, line))
            flag = "COPYLEFT" if lic in COPYLEFT else "note"
            print(f"        -> {flag}: {repo}/{hit['path']} [{lic}]")
        time.sleep(6)  # code search allows 10 requests per minute

    print()
    blocking = [f for f in findings if f[2] in COPYLEFT and ours not in COPYLEFT]
    if not findings:
        print("no external matches. clean.")
        return 0

    print(f"{len(findings)} external match(es), {len(blocking)} under copyleft.\n")
    for repo, hpath, lic, our_path, line in findings:
        print(f"  {our_path}\n    matches {repo}/{hpath} [{lic}]\n    line: {line}\n")

    if blocking and not args.report_only:
        print("FAIL: copyleft match in a non-copyleft project. Check whether this is")
        print("convergent output (standard algorithm, obvious phrasing) or a real")
        print("copy, and record the verdict in THIRD_PARTY.md either way.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
