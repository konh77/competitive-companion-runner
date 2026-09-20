"""Companion runner: executes a snapshot copy of a solution as if it were the original file.

argv: pluginRunner.py <copy_path> <original_path>
"""
import linecache
import os
import sys
import types


def _main() -> None:
    copy_path = sys.argv[1]
    original_path = sys.argv[2]
    original_dir = os.path.dirname(original_path)

    with open(copy_path, "rb") as f:
        source = f.read()

    # Tracebacks must show the snapshot's lines, not whatever the editor holds now.
    text = source.decode("utf-8", errors="replace")
    linecache.cache[original_path] = (len(source), None, text.splitlines(True), original_path)

    os.chdir(original_dir)
    sys.path[0] = original_dir
    sys.argv = [original_path]

    module = types.ModuleType("__main__")
    module.__file__ = original_path
    module.__package__ = None
    module.__spec__ = None
    module.__cached__ = None
    sys.modules["__main__"] = module

    code = compile(source, original_path, "exec")
    exec(code, module.__dict__)


_main()
