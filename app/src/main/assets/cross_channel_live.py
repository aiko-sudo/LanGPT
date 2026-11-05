"""Identify Codex rollouts with a live writer, without opening their contents."""
import os
from pathlib import Path


def active_rollout_paths(candidates, proc_root=Path('/proc'), owner=None):
    """Return candidate paths open for writing by a same-user Codex process.

    Failure or an unavailable proc filesystem means unknown, never active.
    """
    wanted = {str(Path(p)) for p in candidates if p}
    if not wanted:
        return set()
    owner = os.getuid() if owner is None else owner
    found = set()
    try:
        processes = list(proc_root.iterdir())
    except OSError:
        return found
    for process in processes:
        if not process.name.isdigit():
            continue
        try:
            if process.stat().st_uid != owner or Path(os.readlink(process / 'exe')).name != 'codex':
                continue
            for fd in (process / 'fd').iterdir():
                try:
                    target = os.readlink(fd)
                    if target not in wanted or target in found:
                        continue
                    for line in (process / 'fdinfo' / fd.name).read_text().splitlines():
                        if line.startswith('flags:'):
                            mode = int(line.split()[1], 8) & os.O_ACCMODE
                            if mode in (os.O_WRONLY, os.O_RDWR):
                                found.add(target)
                            break
                except (OSError, ValueError, IndexError):
                    continue
        except OSError:
            continue
    return found
