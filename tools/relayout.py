#!/usr/bin/env python3
"""Move problems stored by the plugin to a new path layout and fix their manifests.

Usage:
  relayout.py <project_root> --solution '${contestNumber}-${taskIndex}.py' --tests '.tests/${taskId}' [--apply]

Dry-run by default: prints every planned move. With --apply it moves files, rewrites
problem.json, and deletes .companion/index.json (the plugin rebuilds it on Refresh / restart).

Rules (same as the plugin):
  * A solution file that already exists at the target path is kept ("adopted") when the current
    solution is template-only (just '#' comment lines / blank). If both contain code, nothing is
    moved for that problem and a CONFLICT is reported.
  * Tests directories are moved as a whole.
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
BASE32 = "abcdefghijklmnopqrstuvwxyz234567"


def safe_component(s: str) -> str:
    if re.fullmatch(r"[a-z0-9_-]+", s) and s.split(".")[0].upper() not in RESERVED:
        return s
    data = s.encode("ascii")
    bits = 0
    buf = 0
    out = []
    for b in data:
        buf = (buf << 8) | b
        bits += 8
        while bits >= 5:
            out.append(BASE32[(buf >> (bits - 5)) & 31])
            bits -= 5
    if bits:
        out.append(BASE32[(buf << (5 - bits)) & 31])
    return "~" + "".join(out)


def contest_number(contest_id: str) -> str:
    m = re.fullmatch(r"[A-Za-z]+([0-9]+)", contest_id)
    return m.group(1) if m else contest_id


def variables(m: dict) -> dict:
    task_id = m["taskId"]
    idx = task_id.rsplit("_", 1)[-1] or task_id
    return {
        "contestId": safe_component(m["contestId"]),
        "contestNumber": safe_component(contest_number(m["contestId"])),
        "taskId": safe_component(task_id),
        "taskIndex": safe_component(idx.lower()),
        "taskIndexUpper": safe_component(idx.upper()),
        "date": m.get("receivedAt", "")[:10],
    }


def expand(template: str, vars_: dict) -> str:
    def rep(mo):
        name = mo.group(1)
        if name not in vars_:
            sys.exit(f"unknown variable ${{{name}}}")
        return vars_[name]
    out = re.sub(r"\$\{([A-Za-z0-9]+)}", rep, template.strip())
    if out.startswith("/") or ".." in out.split("/"):
        sys.exit(f"unsafe path from template: {out}")
    return out


OLD_BUILTIN_BODY = "import sys\n\n\ndef main() -> None:\n    input = sys.stdin.readline\n    pass\n\n\nif __name__ == \"__main__\":\n    main()"


def is_template_only(path: Path) -> bool:
    """True for files holding only the header comments, optionally followed by the old v0.1 boilerplate."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return False
    body = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#")).strip()
    return body == "" or body == OLD_BUILTIN_BODY


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--solution", required=True)
    ap.add_argument("--tests", required=True)
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    root = Path(a.root).resolve()

    manifests = [p for p in root.rglob("problem.json") if ".staging" not in p.parts and "samples" not in p.parts]
    plans = []
    for mp in manifests:
        try:
            m = json.loads(mp.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if m.get("kind") != "competitive-companion-problem":
            continue
        v = variables(m)
        new_sol = expand(a.solution, v)
        new_tests = expand(a.tests, v)
        old_sol = root / m["solutionPath"]
        old_tests = root / m["testsDir"]
        if mp.parent != old_tests:
            print(f"SKIP  {m['problemKey']}: manifest at {mp.parent} but declares {m['testsDir']}")
            continue
        plans.append((m, mp, old_sol, root / new_sol, new_sol, old_tests, root / new_tests, new_tests))

    conflicts = 0
    for m, mp, old_sol, new_sol_p, new_sol, old_tests, new_tests_p, new_tests in plans:
        key = m["problemKey"].rsplit("/", 1)[-1]
        sol_action = "keep"
        if old_sol != new_sol_p:
            if new_sol_p.exists():
                if old_sol.exists() and not is_template_only(old_sol):
                    print(f"CONFLICT {key}: both {old_sol.relative_to(root)} and {new_sol} contain code — resolve by hand")
                    conflicts += 1
                    continue
                sol_action = f"adopt {new_sol} (delete template {old_sol.relative_to(root) if old_sol.exists() else '-'})"
            elif old_sol.exists():
                sol_action = f"move {old_sol.relative_to(root)} -> {new_sol}"
            else:
                sol_action = f"missing solution; will point to {new_sol}"
        tests_action = "keep" if old_tests == new_tests_p else f"move {old_tests.relative_to(root)} -> {new_tests}"
        if old_tests != new_tests_p and new_tests_p.exists():
            print(f"CONFLICT {key}: target tests dir {new_tests} already exists")
            conflicts += 1
            continue
        print(f"{key}: solution: {sol_action}; tests: {tests_action}")
        if not a.apply:
            continue
        # apply
        if old_sol != new_sol_p:
            new_sol_p.parent.mkdir(parents=True, exist_ok=True)
            if new_sol_p.exists():
                if old_sol.exists():
                    old_sol.unlink()
            elif old_sol.exists():
                shutil.move(str(old_sol), str(new_sol_p))
        if old_tests != new_tests_p:
            new_tests_p.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old_tests), str(new_tests_p))
            mp = new_tests_p / "problem.json"
        m["solutionPath"] = new_sol
        m["testsDir"] = new_tests
        m["metadataRevision"] = int(m.get("metadataRevision", 1)) + 1
        mp.write_text(json.dumps(m, ensure_ascii=False, indent=2), encoding="utf-8")
        prev = mp.with_name("problem.previous.json")
        if prev.exists():
            prev.unlink()

    if a.apply:
        idx = root / ".companion" / "index.json"
        if idx.exists():
            idx.unlink()
        # remove now-empty old directories
        for d in sorted({p for _, _, s, _, _, t, _, _ in plans for p in (s.parent, t.parent)}, key=lambda p: -len(p.parts)):
            try:
                if d != root and d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
            except OSError:
                pass
        print("done. In PyCharm: Companion tool window -> Refresh Problem Index.")
    else:
        print(f"\ndry-run only ({len(plans)} problems, {conflicts} conflicts). Add --apply to execute.")


if __name__ == "__main__":
    main()
