"""Real Git subprocess acceptance for the isolated workspace experiment."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/git_workspace.py'


class Workspaces(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parent / 'scratch'
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='git-workspace-', dir='/tmp', ignore_cleanup_errors=True)
        self.addCleanup(shutil.rmtree,self.temp.name,ignore_errors=True)
        self.repo = Path(self.temp.name) / 'repo'
        self.repo.mkdir()
        self.git(self.repo, 'init', '-b', 'main')
        self.git(self.repo, 'config', 'user.name', 'Synthetic Test')
        self.git(self.repo, 'config', 'user.email', 'synthetic@example.invalid')
        self.commit(self.repo, 'file', 'base\n')
        # The managed-workspace script strips GIT_* env and disables global config,
        # so a repository whose on-disk owner differs from the running uid (common on
        # NFS exports) makes every git call fail with "dubious ownership". That is a
        # host limitation, not a product defect: skip instead of reporting a false red.
        probe_env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        probe_env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
                         GIT_CONFIG_SYSTEM='/dev/null', LC_ALL='C')
        probe = subprocess.run(['git', '-C', str(self.repo), 'rev-parse', 'HEAD'],
                               capture_output=True, text=True, env=probe_env)
        if 'dubious ownership' in probe.stderr:
            raise unittest.SkipTest('git refuses this NFS workspace (uid mismatch): ' + probe.stderr.strip().splitlines()[0])

    def git(self, path, *args):
        return subprocess.check_output(['git', '-C', str(path), *args], stderr=subprocess.STDOUT).decode().strip()

    def commit(self, path, name, content):
        (Path(path) / name).write_text(content)
        self.git(path, 'add', name)
        self.git(path, 'commit', '-m', 'synthetic change')

    def call(self, action, **params):
        req = dict(action=action, project=str(self.repo))
        req.update(params)
        process = subprocess.run([sys.executable, str(SCRIPT), json.dumps(req)], capture_output=True, check=True)
        self.assertLess(len(process.stdout), 65536)
        self.assertEqual(process.stderr, b'')
        return json.loads(process.stdout)

    def head(self, path=None):
        return self.git(path or self.repo, 'rev-parse', 'HEAD')

    def create(self):
        result = self.call('create', confirmed=True, targetHead=self.head())
        self.assertNotIn('error', result, result)
        return Path(result['created'])

    def merge(self, source, **override):
        args = dict(confirmed=True, source=str(source), targetHead=self.head(), sourceHead=self.head(source))
        args.update(override)
        return self.call('merge', **args)

    def test_status_clean_dirty_and_non_git(self):
        clean = self.call('status')
        self.assertTrue(clean['hasGit'] and clean['isGitRoot'] and clean['ready'], clean)
        self.assertFalse(clean['dirty'])
        (self.repo / 'new').write_text('change')
        dirty = self.call('status')
        self.assertTrue(dirty['dirty'])
        self.assertEqual(dirty['dirtyCount'], 1)
        self.assertFalse(dirty['ready'])
        plain = Path(self.temp.name) / 'plain'
        plain.mkdir()
        non_git = self.call('status', project=str(plain))
        self.assertTrue(non_git['hasGit'])
        self.assertFalse(non_git['isGitRoot'] or non_git['ready'])

    def test_isolation_listing_and_ff(self):
        old = self.head()
        source = self.create()
        self.commit(source, 'new', 'isolated')
        self.assertFalse((self.repo / 'new').exists())
        self.assertEqual(self.head(), old)
        rows = self.call('list')['workspaces']
        self.assertEqual(rows[0]['path'], str(source))
        preview = self.call('preflight', source=str(source))
        self.assertTrue(preview['mergeSupported'], preview)
        self.assertTrue(self.merge(source)['merged'])
        self.assertEqual(self.head(), self.head(source))
        self.assertEqual((self.repo / 'new').read_text(), 'isolated')
        self.assertEqual(self.git(self.repo, 'status', '--porcelain'), '')

    def test_clean_normal_merge_is_preview_only(self):
        source = self.create()
        self.commit(source, 'source', 'source')
        self.commit(self.repo, 'target', 'target')
        old = self.head()
        preview = self.call('preflight', source=str(source))
        self.assertTrue(preview['conflictFree'], preview)
        self.assertFalse(preview['mergeSupported'])
        self.assertIn('error', self.merge(source))
        self.assertEqual(self.head(), old)
        self.assertFalse((self.repo / 'source').exists())

    def test_conflict_refused_target_stays_clean(self):
        source = self.create()
        self.commit(source, 'file', 'source\n')
        self.commit(self.repo, 'file', 'target\n')
        old = self.head()
        preview = self.call('preflight', source=str(source))
        self.assertFalse(preview['conflictFree'])
        self.assertIn('file', preview['conflicts'])
        self.assertIn('file', preview['overlap'])
        self.assertIn('error', self.merge(source))
        self.assertEqual(self.head(), old)
        self.assertEqual(self.git(self.repo, 'status', '--porcelain'), '')
        self.assertFalse((self.repo / '.git/MERGE_HEAD').exists())

    def test_dirty_target_and_source_refused(self):
        source = self.create()
        self.commit(source, 'new', 'source')
        (source / 'untracked').write_text('dirty')
        self.assertIn('error', self.merge(source))
        (self.repo / 'untracked').write_text('dirty')
        self.assertIn('error', self.merge(source))
        self.assertIn('error', self.call('create', confirmed=True, targetHead=self.head()))
        self.assertEqual((source / 'untracked').read_text(), 'dirty')
        self.assertEqual((self.repo / 'untracked').read_text(), 'dirty')

    def test_stale_heads_and_confirmation(self):
        source = self.create()
        old = self.head(source)
        self.commit(source, 'new', 'source')
        self.assertIn('error', self.merge(source, sourceHead=old))
        self.assertIn('error', self.merge(source, targetHead='0'*40))
        self.assertIn('error', self.merge(source, confirmed=False))
        self.assertIn('error', self.call('create', targetHead=self.head()))

    def test_injection_paths_and_unmanaged_sources(self):
        for branch in ('--orphan', 'bad..ref', 'ok;touch malicious'):
            self.assertIn('error', self.call('create', confirmed=True, targetHead=self.head(), branch=branch))
        for path in ('/tmp/escape', '../escape', '--help'):
            self.assertIn('error', self.call('create', confirmed=True, targetHead=self.head(), path=path))
            self.assertIn('error', self.call('preflight', source=path))
        self.assertIn('error', self.call('list', project=str(self.repo)+'/../repo'))
        self.assertEqual(self.call('list')['workspaces'], [])

    def test_app_lock_and_ignored_files(self):
        import fcntl
        lock_path = self.repo / '.git/langpt-workspace.lock'
        with lock_path.open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.call('create', confirmed=True, targetHead=self.head())
            self.assertIn('Another app', result['error'])
        self.commit(self.repo, '.gitignore', 'ignored\n')
        (self.repo / 'ignored').write_text('preserve')
        source = self.create()
        self.assertEqual((self.repo / 'ignored').read_text(), 'preserve')
        (source / 'ignored').write_text('tracked on source')
        self.git(source, 'add', '-f', 'ignored')
        self.git(source, 'commit', '-m', 'fixture ignored collision')
        old = self.head()
        self.assertIn('error', self.merge(source))
        self.assertEqual(self.head(), old)
        self.assertEqual((self.repo / 'ignored').read_text(), 'preserve')
        other = self.create()
        self.commit(other, 'non-conflicting', 'safe')
        self.assertTrue(self.merge(other)['merged'])
        self.assertEqual((self.repo / 'ignored').read_text(), 'preserve')

    def test_non_git_unborn_and_detached(self):
        other = Path(self.temp.name) / 'other'
        other.mkdir()
        self.assertIn('error', self.call('list', project=str(other)))
        self.git(other, 'init')
        self.assertIn('error', self.call('list', project=str(other)))
        self.git(self.repo, 'checkout', '--detach')
        self.assertIn('error', self.call('list'))


class SourceWiring(unittest.TestCase):
    def test_dialog_explains_purpose_and_host_errors(self):
        source=Path(__file__).resolve().parents[1].joinpath(
            'app/src/main/java/app/remotessh/GitWorkspaceDialog.java').read_text()
        self.assertIn('在项目旁建 Git 隔离副本', source)
        self.assertIn('request("status")', source)
        self.assertIn('Not inside a Git working tree', source)
        self.assertIn('explain(error)', source)
        self.assertIn('重试', source)
    def test_workspace_refresh_and_retry_wiring(self):
        source=Path(__file__).resolve().parents[1].joinpath(
            'app/src/main/java/app/remotessh/GitWorkspaceDialog.java').read_text()
        constructor=source.split('private GitWorkspaceDialog(')[1].split('private void close()')[0]
        self.assertNotIn('关闭隔离工作区', constructor)
        self.assertNotIn('ui.icon("close"', constructor)
        self.assertIn('new PullRefreshScrollView(activity, this::refresh', constructor)
        self.assertIn('retry.setVisibility(View.GONE)', constructor)
        self.assertLess(constructor.index('root.addView(scroll'), constructor.index('root.addView(retry)'))
        call=source.split('private void call(')[1].split('private void refresh()')[0]
        self.assertIn('if(statusRequest)retry.setVisibility(View.GONE)', call)
        self.assertIn('if(statusRequest)retry.setVisibility(View.VISIBLE)', call)
        self.assertIn('if(statusRequest)scroll.finishRefresh()', call)
        self.assertIn('call(request("status")', source.split('private void refresh()')[1])
        self.assertNotIn('ui.button("刷新"', source)

    def test_viewer_pinch_blocks_scroll_interception(self):
        source=Path(__file__).resolve().parents[1].joinpath(
            'app/src/main/java/app/remotessh/SftpFileActivity.java').read_text()
        self.assertIn('requestDisallowInterceptTouchEvent(true)', source)
        self.assertIn('onScaleBegin', source)
        self.assertIn('onScaleEnd', source)

if __name__ == '__main__':
    unittest.main()
