"""Recoverable two-file OpenCode configuration update; input is a private file.

The journal is written before either rename. On next invocation an unfinished
transaction is rolled back only if no other writer changed either target.
"""
import fcntl
import json
import os
from pathlib import Path
import stat
import sys
import tempfile

HOME = Path.home()
ROOT = HOME / '.remote-gpt' / 'custom-config'
PATHS = [HOME / '.config/opencode/opencode.json', HOME / '.local/share/opencode/auth.json']
JOURNAL = ROOT / 'pending.json'


def read(path):
    if path.is_symlink():
        raise RuntimeError('配置文件是符号链接，未修改')
    try:
        return path.read_text(encoding='utf-8')
    except FileNotFoundError:
        return None


def sync_dir(path):
    fd = os.open(str(path), os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def replace(path, text):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if text is None:
        try:
            path.unlink()
        except FileNotFoundError:
            return
        sync_dir(path.parent)
        return
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    fd, name = tempfile.mkstemp(prefix='.langpt-', dir=str(path.parent))
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
        sync_dir(path.parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def recover():
    if not JOURNAL.exists():
        return False
    journal = json.loads(JOURNAL.read_text(encoding='utf-8'))
    old, new = journal['old'], journal['new']
    if len(old) != 2 or len(new) != 2:
        raise RuntimeError('恢复记录无效，请手动检查')
    if any(read(path) not in (before, after) for path, before, after in zip(PATHS, old, new)):
        raise RuntimeError('配置在中断后被其他程序修改，请手动检查恢复记录')
    for path, before in zip(PATHS, old):
        replace(path, before)
    JOURNAL.unlink()
    sync_dir(ROOT)
    return True


def apply(request):
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(ROOT, 0o700)
    with (ROOT / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if recover():
            raise RuntimeError('已恢复上次未完成的配置事务，请重新切换')
        old, new = request['old'], request['new']
        if len(old) != 2 or len(new) != 2 or any(not isinstance(x, str) for x in new):
            raise RuntimeError('配置事务无效')
        if [read(path) for path in PATHS] != old:
            raise RuntimeError('远端配置已被修改，请重新切换')
        replace(JOURNAL, json.dumps({'old': old, 'new': new}, ensure_ascii=False))
        try:
            for path, text in zip(PATHS, new):
                replace(path, text)
        except Exception:
            recover()
            raise
        JOURNAL.unlink()
        sync_dir(ROOT)


def main():
    path = Path(sys.argv[1])
    try:
        request = json.loads(path.read_text(encoding='utf-8'))
        apply(request)
        print('OK')
    except Exception as exc:
        print('ERROR: ' + str(exc))
    finally:
        try:
            path.unlink()
        except OSError:
            pass


if __name__ == '__main__':
    main()
