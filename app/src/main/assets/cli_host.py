"""Persistent, fail-closed CLI adapter. Bundled before remote_session.py."""
import shlex
import uuid


class CliHost:
    def __init__(self, folder, command, runtime):
        self.folder, self.runtime = folder, runtime
        self.command = shlex.split(command)
        if not self.command or any('skip-permissions' in a or 'bypassPermissions' in a for a in self.command):
            raise ValueError('不允许绕过 CLI 权限检查')
        self.lock = threading.RLock()
        self.peer = None
        self.active = {}
        self.last_thread = ''
        self.epoch = str(uuid.uuid4())
        self.seq = 0
        self.shutting_down = False
        self.path = folder / 'cli-history.json'
        self.history_error = False
        try:
            self.threads = json.loads(self.path.read_text()) if self.path.exists() else {}
            if not isinstance(self.threads, dict) or any(not isinstance(t, dict) or
                    not isinstance(t.get('turns'), list) for t in self.threads.values()):
                raise ValueError('invalid history')
            if any(not isinstance(turn, dict) or 'status' not in turn or not isinstance(turn.get('items'), list)
                   for thread in self.threads.values() for turn in thread['turns']):
                raise ValueError('invalid turn history')
        except (ValueError, OSError):
            self.threads, self.history_error = {}, True
        for thread in self.threads.values():
            for turn in thread['turns']:
                if turn['status'] == 'inProgress':
                    thread['deliveryUnknown'] = True
                    thread['status'] = {'type': 'idle'}
                    turn['status'] = 'failed'
                    turn['error'] = {'message': '托管进程已结束；送达状态未知，请核对原生历史，勿自动重发'}
        if not self.history_error:
            self.save()

    attach = lambda self, sock: Host.attach(self, sock)

    def save(self):
        temporary = self.path.with_suffix('.new')
        with temporary.open('w') as stream:
            json.dump(self.threads, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(self.path)

    def emit(self, method, **params):
        self.seq += 1
        params.update(remoteEpoch=self.epoch, remoteSeq=self.seq)
        if self.peer:
            self.peer.send(dict(method=method, params=params))

    def receive(self, peer, value):
        with self.lock:
            if peer is not self.peer:
                return
            ident, method = value.get('id'), value.get('method')
            p = value.get('params') or {}
            def reply(result):
                if ident is not None:
                    peer.send(dict(id=ident, result=result))
            try:
                if self.history_error and (method or '').startswith(('thread/', 'turn/')):
                    raise ValueError('适配器历史损坏或不可读；已保留原文件，请先在 PC 恢复，未覆盖历史')
                if self.shutting_down:
                    raise ValueError('适配器已关闭，请重新连接')
                if method == 'initialize':
                    reply({'userAgent': 'langpt-cli/' + self.runtime})
                elif method == 'initialized':
                    return
                elif method == 'remote/session':
                    reply(dict(lastThread=self.last_thread, threads=list(self.active), multiSession=True,
                               safeRestart=True, scriptVersion=SCRIPT_VERSION, sessionFolder=self.folder.name,
                               epoch=self.epoch, capabilities=dict(runtime=self.runtime, models=False,
                               attachments=False, approval=False, interrupt=True, resume=True, steer=False,
                               reason=('DSH 凭据状态未知，请在 PC 配置；' if self.runtime == 'dsh' else '') +
                               'CLI 无手机审批通道，待审批工具拒绝；模型目录、额度和附件未开放')))
                elif method == 'account/read':
                    import shutil
                    if not shutil.which(self.command[0]):
                        raise ValueError('CLI 未安装或不在 PATH 中')
                    if self.runtime == 'claude':
                        result = subprocess.run(self.command + ['auth', 'status', '--json'],
                                                capture_output=True, timeout=15)
                        if result.returncode != 0:
                            raise ValueError('Claude 未登录，请在 PC 完成 claude auth login')
                        status = json.loads(result.stdout)
                        if not status.get('loggedIn'):
                            raise ValueError('Claude 未登录，请在 PC 完成登录')
                    else:
                        reply({'account': None, 'requiresOpenaiAuth': False, 'status': 'unknown',
                               'reason': 'DSH 未提供无模型登录检查；请在 PC 配置凭据，缺凭据会在任务中明确失败'})
                        return
                    reply({'account': {'type': self.runtime}, 'requiresOpenaiAuth': False})
                elif method in ('model/list', 'account/rateLimits/read'):
                    reply({'data': [], 'nextCursor': None, 'reason': 'CLI 未公开可验证目录或额度'})
                elif method == 'thread/start':
                    tid = str(uuid.uuid4())
                    cwd = p.get('cwd') or str(Path.home())
                    if not Path(cwd).is_absolute() or not Path(cwd).is_dir():
                        raise ValueError('项目目录不存在或不是绝对路径')
                    thread = dict(id=tid, cwd=cwd, turns=[], native='', status={'type': 'idle'})
                    self.threads[tid] = thread
                    self.last_thread = tid
                    self.save()
                    reply({'thread': thread})
                elif method == 'thread/list':
                    reply({'data': [{k: v for k, v in t.items() if k not in ('turns', 'native')}
                                   for t in self.threads.values()], 'nextCursor': None})
                elif method == 'remote/replay':
                    # Resume returns an authoritative snapshot under the same lock;
                    # never replay deltas on top of that snapshot.
                    self.emit('remote/replayCompleted', threadId=p.get('threadId', ''))
                    reply({})
                elif method == 'remote/shutdownIfIdle':
                    if self.active or any(t.get('deliveryUnknown') for t in self.threads.values()):
                        raise ValueError('仍有运行任务，拒绝重启')
                    self.shutting_down = True
                    reply({'shutdown': True})
                    threading.Timer(1, lambda: os._exit(0)).start()
                else:
                    tid = p.get('threadId', '')
                    if tid not in self.threads:
                        raise ValueError('未知适配器会话')
                    thread = self.threads[tid]
                    if method in ('thread/read', 'thread/resume'):
                        self.last_thread = tid
                        reply({'thread': thread, 'remoteConfigCurrent': True, 'remoteEpoch': self.epoch, 'remoteSeq': self.seq})
                    elif method in ('thread/delete', 'thread/archive'):
                        if tid in self.active or thread.get('deliveryUnknown'):
                            raise ValueError('运行中或送达未知，不能删除；请先在 PC 核对原生任务')
                        del self.threads[tid]
                        self.save()
                        reply({'deletedAdapterHistoryOnly': True})
                    elif method == 'turn/interrupt':
                        process = self.active.get(tid)
                        if process:
                            self.stop(process)
                        reply({})
                    elif method == 'turn/start':
                        if tid in self.active or thread.get('deliveryUnknown'):
                            raise ValueError('该会话运行中或送达未知；请先在 PC 对账，不要重复发送')
                        if any(x.get('type') != 'text' for x in p.get('input', [])):
                            raise ValueError('此 CLI 适配器不支持附件')
                        prompt = '\n'.join(x.get('text', '') for x in p.get('input', []))
                        if thread['turns'] and not thread['native']:
                            raise ValueError('原生会话身份未知；请先在 PC 核对历史，不自动新建重发')
                        env = dict(os.environ)
                        if self.runtime == 'dsh':
                            # Shipped headless mounts no approval answerer. Source
                            # approval service resolves missing answerers unavailable,
                            # never allowed-once. Only adapter-created sessions resume.
                            env['DSH_PERMISSION_MODE'] = 'workspace-write'
                            argv = self.command + ['--profile', 'headless', '--json']
                            if thread['native']:
                                argv += ['--session-id', thread['native']]
                        else:
                            argv = self.command + ['--print', '--verbose', '--output-format', 'stream-json',
                                                   '--permission-mode', 'manual', '--permission-prompts', 'none']
                            argv += ['--resume', thread['native']] if thread['native'] else ['--session-id', tid]
                        process = subprocess.Popen(argv, cwd=thread['cwd'], stdin=subprocess.PIPE,
                                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                   start_new_session=True, env=env)
                        turn = dict(id=str(uuid.uuid4()), status='inProgress', items=[
                            dict(id=str(uuid.uuid4()), type='userMessage', content=[dict(type='text', text=prompt)])])
                        thread['preview'] = prompt[:80]
                        thread['updatedAt'] = int(time.time())
                        thread['turns'].append(turn)
                        thread['status'] = {'type': 'active'}
                        self.active[tid] = process
                        try:
                            self.save()
                        except OSError:
                            self.stop(process)
                            process.wait()
                            process.stdin.close(); process.stdout.close(); process.stderr.close()
                            self.active.pop(tid, None)
                            thread['turns'].pop()
                            thread['status'] = {'type': 'idle'}
                            raise
                        reply({'turn': turn})
                        self.emit('turn/started', threadId=tid, turn=turn)
                        threading.Thread(target=self.run, args=(tid, turn, process, prompt), daemon=True).start()
                    else:
                        raise ValueError('CLI 不支持此操作')
            except (ValueError, OSError, subprocess.SubprocessError):
                if ident is not None:
                    # No raw subprocess output, commands or credentials in errors.
                    peer.send(dict(id=ident, error=dict(code=-32000, message=str(sys.exc_info()[1])
                        if isinstance(sys.exc_info()[1], ValueError) else 'CLI 启动或预检失败；请在 PC 检查安装和登录')))

    def run(self, tid, turn, process, prompt):
        stderr = bytearray()
        def drain():
            while True:
                data = process.stderr.read(4096)
                if not data:
                    return
                stderr.extend(data)
                del stderr[:-16384]
        drainer = threading.Thread(target=drain, daemon=True)
        drainer.start()
        result_seen = False
        completed_seen = self.runtime != 'dsh'
        failed = False
        try:
            process.stdin.write(prompt.encode())
            process.stdin.close()
            while True:
                line = process.stdout.readline(LIMIT + 1)
                if not line:
                    break
                if len(line) > LIMIT:
                    raise ValueError('event too large')
                event = json.loads(line)
                with self.lock:
                    result, completed, error = apply_cli_event(self, tid, turn, event)
                    result_seen = result_seen or result
                    completed_seen = completed_seen or completed
                    failed = failed or error
            failed = process.wait() != 0 or failed or not result_seen or not completed_seen
        except Exception:
            failed = True
            if process.poll() is None:
                self.stop(process)
            process.wait()
        finally:
            drainer.join(timeout=2)
            process.stdout.close()
            process.stderr.close()
            with self.lock:
                turn['status'] = 'failed' if failed else 'completed'
                if failed:
                    turn['error'] = {'message': 'CLI 未成功完成或工具权限被拒绝；请在 PC 检查模型凭据、CLI 权限和原生历史，勿自动重发'}
                self.threads[tid]['status'] = {'type': 'idle'}
                self.save()
                self.emit('turn/completed', threadId=tid, turn=turn)
                self.active.pop(tid, None)

    def stop(self, process):
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: return
        def reap():
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL)
        threading.Thread(target=reap, daemon=True).start()
