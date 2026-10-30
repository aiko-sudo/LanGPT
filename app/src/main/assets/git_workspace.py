"""Opt-in managed worktrees. argv[1] is the maintenance JSON request.
Only fast-forward writes are supported; divergent merges are preview-only.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid


class Refusal(RuntimeError):
    pass


def git(repo, *args, allowed=(0,)):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_TERMINAL_PROMPT='0', GIT_OPTIONAL_LOCKS='0', GIT_NO_REPLACE_OBJECTS='1',
               GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null', LC_ALL='C')
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        try:
            p = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c',
                                'core.fsmonitor=false', '-C', str(repo), *args],
                               stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                               env=env, timeout=45)
        except FileNotFoundError:
            raise Refusal('Git executable not found on host')
        except subprocess.TimeoutExpired:
            raise Refusal('Git command timed out; inspect state before retrying')
        out.seek(0)
        data = out.read(262145)
        if len(data) > 262144:
            raise Refusal('Repository output exceeds safe limit')
        if p.returncode not in allowed:
            err.seek(0)
            reason = err.read(4096).decode('utf-8', 'replace')
            if 'not a git repository' in reason:
                raise Refusal('Not inside a Git working tree; open the folder that contains .git')
            if 'must be run in a work tree' in reason:
                raise Refusal('Bare repository unsupported; a non-bare working directory is required')
            raise Refusal('Git command failed or unsupported: ' + args[0])
        return p.returncode, data.decode('utf-8', 'surrogateescape').strip('\n')


def value(repo, *args):
    return git(repo, *args)[1]


def canonical(raw):
    if not isinstance(raw, str) or not raw.startswith('/') or any(ord(c) < 32 for c in raw):
        raise Refusal('Absolute canonical path required')
    path = Path(raw)
    if str(path.resolve()) != raw:
        raise Refusal('Noncanonical path rejected')
    return path


def state(raw):
    path = canonical(raw)
    root = value(path, 'rev-parse', '--show-toplevel')
    if root != str(path):
        raise Refusal('Select the repository root')
    if value(path, 'rev-parse', '--is-shallow-repository') != 'false':
        raise Refusal('Shallow repositories unsupported')
    branch = value(path, 'symbolic-ref', '--quiet', '--short', 'HEAD')
    try:
        head = value(path, 'rev-parse', '--verify', 'HEAD^{commit}')
    except Refusal:
        raise Refusal('Unborn repository or invalid HEAD unsupported')
    common = Path(value(path, 'rev-parse', '--git-common-dir'))
    common = (path / common).resolve()
    if (common / 'info/grafts').exists() or value(path, 'for-each-ref', '--format=%(refname)', 'refs/replace'):
        raise Refusal('Grafts and replacement refs unsupported')
    for marker in ('MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply', 'sequencer', 'BISECT_LOG'):
        location = value(path, 'rev-parse', '--git-path', marker)
        if (path / location).exists():
            raise Refusal('Repository operation in progress: ' + marker)
    if value(path, 'ls-files', '--stage').find('160000 ') >= 0:
        raise Refusal('Submodules unsupported')
    if value(path, 'ls-files', '-v').splitlines() and any(
            line[:1] != 'H' for line in value(path, 'ls-files', '-v').splitlines()):
        raise Refusal('Special index flags unsupported')
    dirty = bool(value(path, 'status', '--porcelain=v1', '--untracked-files=all'))
    return dict(path=str(path), branch=branch, head=head, dirty=dirty, common=str(common))


def managed_root(s):
    return Path(s['common']) / 'langpt-workspaces'


def workspaces(s):
    records = value(s['path'], 'worktree', 'list', '--porcelain').split('\n\n')
    result = []
    for record in records:
        fields = record.split('\n')
        row = dict(x.split(' ', 1) for x in fields if ' ' in x)
        path = row.get('worktree', '')
        branch = row.get('branch', '')
        if (Path(path).parent == managed_root(s) and
                re.fullmatch(r'workspace-[0-9a-f]{32}', Path(path).name) and
                branch == 'refs/heads/langpt/' + Path(path).name):
            result.append(dict(path=path, branch=branch, head=row.get('HEAD', '')))
    return result


def pair(request, target):
    source_path = request.get('source')
    if source_path not in [w['path'] for w in workspaces(target)]:
        raise Refusal('Source must be an existing managed workspace')
    source = state(source_path)
    if source['common'] != target['common'] or source['path'] == target['path']:
        raise Refusal('Invalid source repository')
    return source


def preflight(target, source):
    repo = target['path']
    base = value(repo, 'merge-base', target['head'], source['head'])
    def changed(head):
        return set(filter(None, value(repo, 'diff', '--name-only', '-z', base, head).split('\x00')))
    # Trial merge owns a disposable clone, index and checkout. Real refs, index
    # and working trees are never used by the trial, even when it conflicts.
    with tempfile.TemporaryDirectory(prefix='langpt-merge-check-') as scratch:
        trial = Path(scratch) / 'trial'
        git(repo, 'clone', '--shared', '--no-checkout', '--', repo, str(trial))
        git(trial, 'checkout', '--detach', target['head'])
        git(trial, 'config', 'user.name', 'LanGPT preflight')
        git(trial, 'config', 'user.email', 'preflight@example.invalid')
        code, _ = git(trial, 'merge', '--no-commit', '--no-ff', source['head'], allowed=(0, 1))
        conflicts = list(filter(None, value(trial, 'diff', '--name-only', '--diff-filter=U', '-z').split('\x00')))
        if code and not conflicts:
            raise Refusal('Synthetic merge failed without conflict paths; repository unsupported')
    ff = git(repo, 'merge-base', '--is-ancestor', target['head'], source['head'], allowed=(0, 1))[0] == 0
    return dict(target=target, source=source, overlap=sorted(changed(target['head']) & changed(source['head'])),
                conflicts=conflicts, conflictFree=code == 0, fastForward=ff,
                mergeSupported=ff and code == 0 and not target['dirty'] and not source['dirty'],
                confirmationNeeded=True, limitation='Only fast-forward merge writes are supported')


def status(raw):
    path = canonical(raw)
    result = dict(hasGit=True, isGitRoot=False, dirty=False, dirtyCount=0,
                  branch='', head='', inProgress=False, ready=False, reason='')
    try:
        root = value(path, 'rev-parse', '--show-toplevel')
    except Refusal as error:
        if 'not found on host' in str(error):
            result['hasGit'] = False
        elif 'Not inside a Git' not in str(error):
            raise
        return result
    result['isGitRoot'] = root == str(path)
    if not result['isGitRoot']:
        return result
    result['branch'] = git(path, 'symbolic-ref', '--quiet', '--short', 'HEAD', allowed=(0, 1))[1]
    result['head'] = git(path, 'rev-parse', '--verify', 'HEAD^{commit}', allowed=(0, 128))[1]
    entries = value(path, 'status', '--porcelain=v1', '-z', '--untracked-files=all').split('\0')
    # Rename/copy entries have an extra NUL-separated path.
    count = 0
    i = 0
    while i < len(entries) and entries[i]:
        entry = entries[i]; count += 1; i += 1
        if entry[0:1] in ('R', 'C') or entry[1:2] in ('R', 'C'):
            i += 1
    result['dirtyCount'] = count
    result['dirty'] = count > 0
    for marker in ('MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply', 'sequencer', 'BISECT_LOG'):
        if (path / value(path, 'rev-parse', '--git-path', marker)).exists():
            result['inProgress'] = True
    try:
        state(raw)
    except Refusal as error:
        result['reason'] = str(error)
    result['ready'] = not result['dirty'] and not result['inProgress'] and not result['reason']
    return result


def execute(request):
    action = request.get('action', 'list')
    if action == 'status':
        return status(request.get('project'))
    target = state(request.get('project'))
    if action == 'list':
        return dict(target=target, workspaces=workspaces(target))
    if action == 'preflight':
        return preflight(target, pair(request, target))
    if action not in ('create', 'merge') or request.get('confirmed') is not True:
        raise Refusal('Explicit confirmation required')
    # No user branch/path overrides are accepted, even if otherwise valid refs.
    if 'branch' in request or 'path' in request:
        raise Refusal('Workspace branch and path are app-generated only')
    lock_path = Path(target['common']) / 'langpt-workspace.lock'
    if lock_path.is_symlink():
        raise Refusal('Unsafe lock path')
    fd = os.open(str(lock_path), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('Another app workspace operation is running')
        target = state(request['project'])
        if target['dirty']:
            raise Refusal('Target working tree must be clean')
        if request.get('targetHead') != target['head']:
            raise Refusal('Target HEAD changed; refresh preflight')
        if action == 'create':
            folder = managed_root(target)
            if folder.is_symlink() or folder.resolve() != folder:
                raise Refusal('Unsafe workspace root')
            folder.mkdir(mode=0o700, exist_ok=True)
            name = 'workspace-' + uuid.uuid4().hex
            branch = 'langpt/' + name
            git(target['path'], 'check-ref-format', '--branch', branch)
            path = folder / name
            git(target['path'], 'worktree', 'add', '-b', branch, str(path), target['head'])
            return dict(created=str(path), branch=branch, confirmationNeeded=True)
        source = pair(request, target)
        if source['dirty']:
            raise Refusal('Source working tree must be clean')
        if request.get('sourceHead') != source['head']:
            raise Refusal('Source HEAD changed; refresh preflight')
        preview = preflight(target, source)
        if not preview['mergeSupported']:
            raise Refusal('Merge refused: conflicts or divergent history; only clean fast-forward supported')
        # Recheck immediately before Git obtains its own index/ref locks. External
        # Git writers must be paused: this app lock cannot serialize other tools.
        if state(target['path']) != target or state(source['path']) != source:
            raise Refusal('Repository changed during preflight')
        git(target['path'], 'merge', '--ff-only', '--no-edit', '--no-overwrite-ignore', source['head'])
        return dict(merged=True, target=state(target['path']), source=source)


def main():
    try:
        request = json.loads(sys.argv[1])
        if not isinstance(request, dict):
            raise Refusal('Object request required')
        result = execute(request)
    except Exception as error:
        result = {'error': str(error) if isinstance(error, Refusal) else 'Workspace operation failed; inspect repository before retrying'}
    encoded = json.dumps(result, ensure_ascii=True)
    if len(encoded.encode('utf-8')) >= 65536:
        encoded = json.dumps({'error': 'Result exceeds 64 KiB; use a smaller repository'})
    print(encoded)


if __name__ == '__main__':
    main()
