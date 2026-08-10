#!/usr/bin/env python3
"""Provenance gate: does code in this repo also live in someone else's repo?

The worry this answers is narrow and real: an assistant reproduces a chunk of
somebody's source from memory, it lands here, and the first time anyone notices
is a letter. So before a release we take the most distinctive lines that are new
since the last tag, ask GitHub code search which other repositories contain the
same unusual identifiers together, and look at the license of what comes back.

Identifiers rather than whole lines, because the code search API is token based
and has no phrase matching: a quoted line returns nothing even when the file it
would match is in the index. Two rare names co-occurring is a question it can
answer, and rare enough that an answer means something.

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

# An identifier applied to a literal. These make the sharpest queries, so a line
# carrying one earns a shorter length floor and a scoring bonus: `out.push(
# sextet(18))` is twenty characters and worth more than any sixty-character line
# of framework calls around it.
CALLFORM = re.compile(r"[A-Za-z_]\w{3,}[\(\[](?:0x[0-9a-fA-F]+|\d+)[\)\]]")


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
        has_call = bool(CALLFORM.search(s))
        if not ((15 if has_call else 45) <= len(s) <= 130):
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
        # The call form is the only probe with real precision, so it outranks
        # everything. Generic tokens are capped, or a long line of framework
        # calls beats a short line of arithmetic purely on word count.
        score = 5 * algo + 2 * min(generic, 6) - 6 * ours + (20 if has_call else 0)
        if score <= 0:
            continue
        scored.append((score, path, s))

    scored.sort(key=lambda x: -x[0])
    return [(p, s) for _, p, s in scored[:limit]]


# Tokens that appear in every project in the language and carry no signal.
STOPWORDS = {
    "string", "vec", "self", "value", "result", "option", "error", "none",
    "true", "false", "null", "async", "await", "print", "println", "format",
    "index", "count", "length", "size", "name", "path", "file", "data",
    "buffer", "reader", "writer", "iter", "next", "clone", "unwrap", "expect",
    "map", "filter", "collect", "push", "insert", "remove", "update", "delete",
    "start", "stop", "state", "config", "context", "handler", "request",
    "response", "client", "server", "message", "params", "args", "kwargs",
    "usize", "isize", "u8", "u16", "u32", "u64", "i32", "i64", "f32", "f64",
    "bool", "char", "byte", "bytes", "int", "float", "str", "list", "dict",
    "set", "type", "class", "object", "static", "const", "public", "private",
    "return", "yield", "raise", "throw", "catch", "finally", "import", "export",
}

LANGUAGE = {
    ".rs": "rust", ".py": "python", ".ts": "typescript", ".tsx": "typescript",
    ".js": "javascript", ".jsx": "javascript", ".swift": "swift",
    ".kt": "kotlin", ".java": "java", ".go": "go", ".rb": "ruby",
    ".c": "c", ".h": "c", ".cc": "cpp", ".cpp": "cpp", ".hpp": "cpp",
    ".css": "css", ".scss": "scss", ".sh": "shell",
}


def probe_terms(line: str) -> list[str]:
    """The two or three least ordinary identifiers on a line.

    GitHub's code search API is token based. It has no phrase matching: a
    quoted line comes back empty even when the file it was copied from is
    sitting in the index, which is how the first version of this script managed
    to report clean on a line that does exist elsewhere. So the probe is a
    co-occurrence instead. Two unusual identifiers on one line, ANDed, is rare
    enough to mean something and is a query the API actually answers.

    Names this repo declares are deliberately not excluded. A name we invented
    turning up in a stranger's file is the strongest signal available, and
    dropping those is what made the base64 case invisible to an earlier version
    of this script. Longer names first: length is the cheapest proxy for rarity.
    """
    # `sextet(18)` returns exactly one repository on the whole of GitHub, where
    # `sextet ALPHABET` returns every base64 implementation ever written.
    calls = CALLFORM.findall(line)
    if calls:
        return [max(calls, key=len)]

    seen, terms = set(), []
    for t in re.findall(r"[A-Za-z_][A-Za-z0-9_]{4,}", line):
        low = t.lower()
        if low in STOPWORDS or low in seen:
            continue
        seen.add(low)
        terms.append(t)
    terms.sort(key=len, reverse=True)
    return terms[:3]


def search(terms: list[str], lang: str | None) -> list[dict]:
    """Ask code search for files where all of `terms` occur."""
    q = " ".join(terms)
    if lang:
        q += f" language:{lang}"
    r = subprocess.run(
        ["gh", "api", "-X", "GET", "search/code", "-f", f"q={q}",
         "--jq", "{total: .total_count, items: [.items[]? | "
                 "{repo: .repository.full_name, path: .path}]}"],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        if "rate limit" in r.stderr.lower():
            print("  ! code search rate limit reached, pausing 60s", file=sys.stderr)
            time.sleep(60)
            return search(terms, lang)
        return []
    try:
        payload = json.loads(r.stdout or "{}")
    except json.JSONDecodeError:
        return []
    # A combination that matches half of GitHub was never distinctive. Reporting
    # it would bury the one result that matters under fifty that do not. Say so
    # out loud: a probe that was skipped is not a probe that came back clean.
    total = payload.get("total", 0)
    if total > 40:
        print(f"        (too common to be evidence: {total} repositories)")
        return []
    return payload.get("items", [])


def recorded() -> str:
    """THIRD_PARTY.md, if there is one.

    A finding that has been investigated and written up should not block every
    release afterwards. Naming the repository in THIRD_PARTY.md is what closes
    it, which puts the escape hatch in the file a reader would consult anyway
    rather than in a dotfile nobody opens.
    """
    for name in ("THIRD_PARTY.md", "THIRD_PARTY_NOTICES.md", "NOTICE.md"):
        p = Path(name)
        if p.exists():
            return p.read_text(errors="replace")
    return ""


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
    symbols = project_symbols()

    # Several lines often reduce to the same pair of unusual identifiers. Asking
    # the same question twice costs a query out of ten per minute.
    # Rank probes by how much an answer would mean, not by how the line scored.
    # A call form built on a name we invented is the sharpest question available:
    # `sextet(18)` matches one repository on GitHub, `unwrap_or(0)` matches nine
    # hundred thousand. Both are call forms; only one is evidence.
    def tier(terms: list[str]) -> int:
        head = re.match(r"[A-Za-z_]\w*", terms[0]).group(0)
        call = bool(CALLFORM.fullmatch(terms[0]))
        ours = any(re.match(r"[A-Za-z_]\w*", x).group(0) in symbols for x in terms)
        if call and head in symbols:
            return 0
        if not call and ours:
            return 1
        return 2 if call else 3

    scored_probes, asked = [], set()
    for path, line in candidate_lines(base, args.limit * 8):
        terms = probe_terms(line)
        # A call form stands alone: `sextet(18)` is already specific enough to
        # be worth a query, and demanding a second term is what silently threw
        # away every sharp probe in an earlier version.
        if not terms or (len(terms) < 2 and not CALLFORM.fullmatch(terms[0])):
            continue
        terms = terms[:1] if CALLFORM.fullmatch(terms[0]) else terms[:2]
        key = frozenset(x.lower() for x in terms)
        if key in asked:
            continue
        asked.add(key)
        scored_probes.append((tier(terms), path, line, terms,
                              LANGUAGE.get(Path(path).suffix)))

    scored_probes.sort(key=lambda x: x[0])
    probes = [(p, l, tm, lg) for _, p, l, tm, lg in scored_probes[:args.limit]]

    scope = "all tracked source" if base is None else f"changes since {base}"
    print(f"provenance-check: {scope}, license {ours}, {len(probes)} probes\n")
    if not probes:
        print("nothing distinctive enough to probe. clean.")
        return 0

    known = recorded()
    findings = []
    for i, (path, line, terms, lang) in enumerate(probes, 1):
        print(f"[{i}/{len(probes)}] {path}: {' + '.join(terms)}")
        for hit in search(terms, lang):
            repo = hit["repo"]
            if repo.split("/")[0].lower() == owner:
                continue
            lic = license_of(repo)
            seen_before = repo in known
            findings.append((repo, hit["path"], lic, path, line, seen_before))
            if seen_before:
                flag = "recorded"
            elif lic in COPYLEFT:
                flag = "COPYLEFT"
            else:
                flag = "note"
            print(f"        -> {flag}: {repo}/{hit['path']} [{lic}]")
        time.sleep(7)  # code search allows 10 requests per minute

    print()
    blocking = [f for f in findings
                if f[2] in COPYLEFT and ours not in COPYLEFT and not f[5]]
    if not findings:
        print("no external matches. clean.")
        return 0

    new = [f for f in findings if not f[5]]
    print(f"{len(findings)} external match(es), {len(findings) - len(new)} already "
          f"recorded in THIRD_PARTY.md, {len(blocking)} blocking.\n")
    for repo, hpath, lic, our_path, line, seen_before in findings:
        mark = "  (recorded)" if seen_before else ""
        print(f"  {our_path}{mark}\n    matches {repo}/{hpath} [{lic}]\n    line: {line}\n")

    if blocking and not args.report_only:
        print("FAIL: copyleft match in a non-copyleft project. Check whether this is")
        print("convergent output (standard algorithm, obvious phrasing) or a real")
        print("copy, and record the verdict in THIRD_PARTY.md either way.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
