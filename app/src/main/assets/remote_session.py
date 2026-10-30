#!/usr/bin/env python3
"""Private, SSH-only app-server host. Detaching a client never closes server stdin.

One connection owns a profile at a time. Reattachment replaces that connection,
remaps request IDs, and replays a thread's baseline and ordered event journal.
Only the daemon lifetime is durable; machine/daemon failure still ends the task.
"""
import base64
import fcntl
import hashlib
import json
import os
import signal
import select
import socket
import sqlite3
import urllib.request
import subprocess
import sys
import threading
import tempfile
import time
from pathlib import Path

LIMIT = 32 * 1024 * 1024
SCRIPT_VERSION = 7  # bump when host bridge behavior changes; idle daemons auto-upgrade


def encode(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def proxy_env():
    """Optional host-side proxy for the agent backend (e.g. chatgpt.com).

    The PC owner opts in by writing a URL like http://127.0.0.1:7890 into
    ~/.codex/proxy.env. The file is read each time a backend process starts,
    so changing or emptying it needs no app update. Only unset variables are
    filled, never overriding an explicitly exported proxy.
    """
    try:
        url = (Path.home() / '.codex' / 'proxy.env').read_text().strip()
    except OSError:
        url = ''
    if not url:
        return None
    env = dict(os.environ)
    env.setdefault('HTTPS_PROXY', url)
    env.setdefault('HTTP_PROXY', url)
    return env


class Peer:
    def __init__(self, sock):
        self.sock = sock
        self.out = tempfile.TemporaryFile()
        self.condition = threading.Condition()
        self.written = 0
        self.read_at = 0
        self.alive = True
        self.resuming = None
        self.replay_since = 0
        self.pause_seq = 0
        threading.Thread(target=self.write, daemon=True).start()

    def send(self, value):
        data = encode(value)
        with self.condition:
            if not self.alive:
                return
            self.out.seek(self.written)
            self.out.write(data)
            self.written += len(data)
            self.condition.notify()

    def write(self):
        try:
            while True:
                with self.condition:
                    while self.alive and self.read_at == self.written:
                        self.condition.wait()
                    if not self.alive:
                        break
                    self.out.seek(self.read_at)
                    data = self.out.read(min(65536, self.written - self.read_at))
                    self.read_at += len(data)
                    if self.read_at == self.written:
                        self.out.seek(0)
                        self.out.truncate()
                        self.read_at = self.written = 0
                # MSG_DONTWAIT is per send, unlike settimeout/setblocking which
                # also changes the concurrent reader's socket state.
                pending = memoryview(data)
                deadline = time.monotonic() + 10
                while pending:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError('peer send stalled')
                    if not select.select([], [self.sock], [], remaining)[1]:
                        raise TimeoutError('peer send stalled')
                    try:
                        count = self.sock.send(pending, socket.MSG_DONTWAIT)
                    except (BlockingIOError, InterruptedError):
                        continue
                    if count <= 0:
                        raise OSError('peer socket closed')
                    pending = pending[count:]
        except OSError:
            pass
        finally:
            self.close()
            self.out.close()

    def close(self):
        with self.condition:
            self.alive = False
            self.condition.notify()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class Ws:
    """Minimal stdlib WebSocket client over a Unix socket (codex shared daemon)."""

    GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

    def __init__(self, sock):
        self.sock = sock
        self.buffer = b''
        key = base64.b64encode(os.urandom(16)).decode()
        request = ('GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n'
                   'Connection: Upgrade\r\nSec-WebSocket-Key: ' + key +
                   '\r\nSec-WebSocket-Version: 13\r\n\r\n')
        sock.sendall(request.encode())
        data = b''
        while b'\r\n\r\n' not in data:
            chunk = sock.recv(4096)
            if not chunk:
                raise OSError('websocket handshake eof')
            data += chunk
        head, _, rest = data.partition(b'\r\n\r\n')
        if b' 101 ' not in head.split(b'\r\n', 1)[0]:
            raise OSError('websocket upgrade failed')
        self.buffer = rest

    def _exact(self, count, timeout):
        self.sock.settimeout(timeout)
        while len(self.buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise OSError('eof')
            self.buffer += chunk

    def read_json(self, timeout):
        """Read one text frame as JSON; pings are answered, skips are None."""
        self._exact(2, timeout)
        opcode = self.buffer[0] & 0x0f
        length = self.buffer[1] & 0x7f
        index = 2
        if length == 126:
            self._exact(4, timeout)
            length = int.from_bytes(self.buffer[2:4], 'big')
            index = 4
        elif length == 127:
            self._exact(10, timeout)
            length = int.from_bytes(self.buffer[2:10], 'big')
            index = 10
        if self.buffer[1] & 0x80:
            index += 4
        self._exact(index + length, timeout)
        payload = self.buffer[index:index + length]
        self.buffer = self.buffer[index + length:]
        if opcode == 9:
            self.send_frame(10, payload)
            return None
        if opcode == 8:
            raise OSError('websocket closed by peer')
        if opcode in (0, 1, 2) and payload:
            return json.loads(payload.decode('utf-8', 'replace'))
        return None

    def send_frame(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        count = len(payload)
        mask = os.urandom(4)
        if count < 126:
            header.append(0x80 | count)
        elif count < 65536:
            header.append(0x80 | 126)
            header += count.to_bytes(2, 'big')
        else:
            header.append(0x80 | 127)
            header += count.to_bytes(8, 'big')
        header += mask
        self.sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send_json(self, value):
        self.send_frame(1, json.dumps(value, separators=(',', ':')).encode())


def normalize_tui(result):
    """Lenient projection of thread/loaded/list results for the phone list."""
    threads = []
    rows = []
    if isinstance(result, dict):
        for key in ('threads', 'data', 'items', 'loadedThreads', 'threadIds'):
            value = result.get(key)
            if isinstance(value, list):
                rows = value
                break
    active_ids = set()
    if isinstance(result, dict):
        live = result.get('activeThreadIds') or result.get('active')
        if isinstance(live, list):
            for entry in live:
                active_ids.add(entry if isinstance(entry, str)
                               else (entry or {}).get('threadId') or (entry or {}).get('id') or '')
    for row in rows:
        if isinstance(row, str):
            threads.append({'id': row, 'title': '', 'active': row in active_ids})
            continue
        if not isinstance(row, dict):
            continue
        thread_id = row.get('threadId') or row.get('id') or row.get('thread_id')
        if not thread_id:
            continue
        status = row.get('status')
        active = (thread_id in active_ids or status == 'active'
                  or (isinstance(status, dict) and status.get('type') == 'active'))
        threads.append({'id': thread_id,
                        'title': str(row.get('title') or row.get('name') or ''),
                        'active': bool(active)})
    return {'threads': threads}


class TuiWatcher:
    """Read-only watcher for the codex shared app-server daemon (PC TUI sessions).

    Attach only when the control socket already exists, so a running TUI keeps
    the originator role. Polls thread/loaded/list and forwards a normalized
    snapshot to the phone as remote/tuiThreads. Any failure degrades silently.
    """

    INTERVAL = 5.0
    RETRY = 30.0

    def __init__(self, notify):
        self.notify = notify
        self.stopping = threading.Event()
        threading.Thread(target=self.run, daemon=True).start()

    def stop(self):
        self.stopping.set()

    def run(self):
        while not self.stopping.is_set():
            try:
                self.cycle()
            except Exception:
                pass
            self.stopping.wait(self.RETRY)

    def cycle(self):
        control = Path.home() / '.codex' / 'app-server-control' / 'app-server-control.sock'
        if not control.exists():
            raise FileNotFoundError('no codex daemon socket')
        sock = socket.socket(socket.AF_UNIX)
        try:
            sock.settimeout(10)
            sock.connect(str(control))
            ws = Ws(sock)
            ws.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {
                'clientInfo': {'name': 'remote_ssh_android_watch', 'title': 'Remote watch', 'version': '1'},
                'capabilities': {'experimentalApi': True,
                                 'optOutNotificationMethods': ['item/agentMessage/delta',
                                                               'item/reasoning/textDelta']}}})
            while True:
                message = ws.read_json(8)
                if message and message.get('id') == 1:
                    if message.get('error'):
                        raise OSError('daemon initialize rejected')
                    break
            ws.send_json({'jsonrpc': '2.0', 'method': 'initialized', 'params': {}})
            ide_ipc = (Path.home() / '.codex' / 'ipc' / 'ipc.sock').exists()
            sequence = 2
            last = None
            while not self.stopping.is_set():
                ws.send_json({'jsonrpc': '2.0', 'id': sequence, 'method': 'thread/loaded/list',
                              'params': {}})
                sequence += 1
                result = None
                deadline = time.time() + 6
                while time.time() < deadline:
                    message = ws.read_json(max(0.5, deadline - time.time()))
                    if message and message.get('id') == sequence - 1:
                        result = message.get('result')
                        break
                payload = normalize_tui(result)
                payload['ideIpc'] = ide_ipc
                fingerprint = json.dumps(payload, sort_keys=True)
                if fingerprint != last:
                    last = fingerprint
                    self.notify(payload)
                quiet_until = time.time() + self.INTERVAL
                while not self.stopping.is_set() and time.time() < quiet_until:
                    message = ws.read_json(min(1.0, max(0.2, quiet_until - time.time())))
                    if message:
                        quiet_until = time.time() + 1.0
        finally:
            try:
                sock.close()
            except OSError:
                pass


class Host:
    def __init__(self, folder, command):
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.folder = folder
        self.lock = threading.RLock()
        self.peer = None
        self.pending = {}
        self.approvals = {}
        self.active = set()
        self.shutting_down = False
        self.sequence = 0
        self.initialized = False
        self.initialization = None
        self.initializing = False
        self.init_waiters = []
        self.baselines = {}
        self.last_thread = ''
        # Old journal cannot represent a new app-server process. Its real history
        # remains in Codex; only this daemon's live events belong in this journal.
        self.db = sqlite3.connect(folder / 'events.sqlite', check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=NORMAL')
        self.db.execute('DROP TABLE IF EXISTS events')
        self.db.execute('CREATE TABLE events (seq INTEGER PRIMARY KEY, thread TEXT, body TEXT)')
        self.db.execute('CREATE INDEX by_thread ON events(thread, seq)')
        self.db.commit()
        self.process = subprocess.Popen(command, shell=True, executable='/bin/sh',
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=sys.stderr, start_new_session=True,
                                        env=proxy_env() or None)
        (folder / 'server.pid').write_text(str(self.process.pid))
        threading.Thread(target=self.read, daemon=True).start()
        # Runtime seam: read-only observer of codex shared daemon (PC TUI sessions).
        self.tui = TuiWatcher(self.push_tui)

    def push_tui(self, payload):
        with self.lock:
            if self.peer is not None and not self.shutting_down:
                self.peer.send(dict(method='remote/tuiThreads', params=payload))

    def upstream(self, value):
        self.process.stdin.write(encode(value))
        self.process.stdin.flush()

    def receive(self, peer, value):
        with self.lock:
            if peer is not self.peer:
                return
            method = value.get('method')
            ident = value.get('id')
            params = value.get('params') or {}
            if self.shutting_down:
                if ident is not None:
                    peer.send(dict(id=ident, error=dict(code=-32000, message='远端正在安全重启，请稍候重连')))
                return
            if method == 'remote/shutdownIfIdle':
                if (not self.initialized or self.active or self.approvals or peer.resuming
                        or any(p[2].startswith(('turn/', 'thread/')) for p in self.pending.values())):
                    peer.send(dict(id=ident, error=dict(code=-32000, message='远端仍有任务或恢复请求，未执行重启')))
                else:
                    self.shutting_down = True
                    peer.send(dict(id=ident, result={'shutdown': True}))
                    def stop():
                        try:
                            os.killpg(self.process.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                    threading.Timer(1.0, stop).start()
                return
            if method == 'initialize' and self.initialization is not None:
                peer.send(dict(id=ident, result=self.initialization))
                return
            if method == 'initialize':
                if self.initializing:
                    self.init_waiters.append((peer, ident))
                    return
                self.initializing = True
            if method == 'initialized':
                if not self.initialized:
                    self.upstream(value)
                    self.initialized = True
                return
            if method == 'remote/session':
                peer.send(dict(id=ident, result={'lastThread': self.last_thread,
                    'threads': list(self.baselines), 'multiSession': True,
                    'safeRestart': True, 'sessionFolder': self.folder.name,
                    'scriptVersion': SCRIPT_VERSION,
                    'capabilities': {'runtime': 'codex', 'models': True, 'attachments': True,
                                     'approval': True, 'resume': True, 'interrupt': True}}))
                return
            thread = params.get('threadId', '')
            if method == 'thread/resume' and thread in self.baselines:
                baseline, since = self.baselines[thread]
                self.last_thread = thread
                peer.resuming = thread
                peer.replay_since = since
                peer.pause_seq = self.db.execute('SELECT COALESCE(MAX(seq),0) FROM events').fetchone()[0]
                peer.send(dict(id=ident, result=dict(baseline, remoteReplay=True, remoteConfigCurrent=True)))
                return
            if method == 'remote/replay':
                if peer.resuming != thread:
                    return
                since = peer.replay_since
                peer.send(dict(method='remote/replayStarted', params={'threadId': thread}))
                for (body,) in self.db.execute('SELECT body FROM events WHERE thread=? AND seq>? ORDER BY seq', (thread, since)):
                    event = json.loads(body)
                    # Approval requests are replayed only if still awaiting an answer.
                    if 'id' not in event:
                        peer.send(event)
                for request in self.approvals.values():
                    peer.send(request)
                # Other subscribed threads kept running while the client applied
                # this baseline. Flush their journal tail before resuming delivery.
                for (body,) in self.db.execute('SELECT body FROM events WHERE thread!=? AND seq>? ORDER BY seq', (thread, peer.pause_seq)):
                    event = json.loads(body)
                    if 'id' not in event:
                        peer.send(event)
                peer.send(dict(method='remote/replayCompleted', params={'threadId': thread}))
                peer.resuming = None
                return
            if method and ident is not None:
                self.sequence += 1
                # Keep ownership until Codex confirms unsubscribe. Dropping the
                # baseline before the response can strand a writer in this very
                # app-server when unsubscribe is rejected or unsupported; after
                # reconnect, forwarding resume then reports "active writer".
                self.pending[self.sequence] = (peer, ident, method, thread, dict(params))
                value['id'] = self.sequence
            elif not method and ident is not None:
                if str(ident) not in self.approvals:
                    return  # Stale or duplicate approval must never reach Codex.
                self.approvals.pop(str(ident), None)
            self.upstream(value)

    def read(self):
        try:
            while True:
                line = self.process.stdout.readline(LIMIT + 1)
                if not line:
                    break
                if len(line) > LIMIT:
                    raise ValueError('app-server message exceeds 32 MiB')
                value = json.loads(line)
                with self.lock:
                    method = value.get('method')
                    ident = value.get('id')
                    if not method:
                        pending = self.pending.pop(ident, None)
                        if pending is None:
                            continue
                        peer, original, request, request_thread, request_params = pending
                        result = value.get('result')
                        if request == 'initialize':
                            self.initializing = False
                            for waiting_peer, waiting_id in self.init_waiters:
                                if waiting_peer is self.peer:
                                    waiting_peer.send(dict(value, id=waiting_id))
                            self.init_waiters.clear()
                        if result is not None:
                            if request == 'initialize':
                                self.initialization = result
                            if request == 'turn/start' and result.get('turn', {}).get('status', 'inProgress') == 'inProgress':
                                self.active.add(request_thread)
                            if request == 'turn/start' and request_thread in self.baselines:
                                baseline, since = self.baselines[request_thread]
                                updated = dict(baseline)
                                for source, target in [('model', 'model'), ('effort', 'reasoningEffort'), ('serviceTier', 'serviceTier')]:
                                    if source in request_params:
                                        updated[target] = request_params[source]
                                self.baselines[request_thread] = (updated, since)
                            if request in ('thread/start', 'thread/resume'):
                                thread = result.get('thread', {}).get('id')
                                if thread:
                                    seq = self.db.execute('SELECT COALESCE(MAX(seq),0) FROM events').fetchone()[0]
                                    self.baselines[thread] = (result, seq)
                                    self.last_thread = thread
                                    if result.get('thread', {}).get('status', {}).get('type') == 'active':
                                        self.active.add(thread)
                        if request == 'thread/unsubscribe' and 'error' not in value:
                            self.baselines.pop(request_thread, None)
                        value['id'] = original
                        if peer is self.peer:
                            peer.send(value)
                        continue
                    params = value.get('params') or {}
                    thread = params.get('threadId', '')
                    if ident is not None:
                        self.approvals[str(ident)] = value
                    if method == 'serverRequest/resolved':
                        self.approvals.pop(str(params.get('requestId')), None)
                    if method == 'thread/status/changed' and params.get('status', {}).get('type') == 'active':
                        self.active.add(thread)
                    if method == 'turn/started':
                        self.active.add(thread)
                    if method == 'turn/completed':
                        self.active.discard(thread)
                        self.approvals = {k: v for k, v in self.approvals.items()
                                          if v.get('params', {}).get('threadId') != thread}
                    if method == 'thread/closed':
                        self.active.discard(thread)
                        self.baselines.pop(thread, None)
                    self.db.execute('INSERT INTO events(thread,body) VALUES (?,?)', (thread, json.dumps(value)))
                    self.db.commit()
                    if self.peer and self.peer.resuming is None:
                        self.peer.send(value)
        finally:
            if self.peer:
                self.peer.close()
            # A live descendant would keep thread writers after the host dies.
            # Reap the whole backend session before allowing a replacement host.
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
            try:
                # The shell may have exited while a Codex descendant survived.
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os._exit(1)

    def attach(self, sock):
        peer = Peer(sock)
        with self.lock:
            if self.peer:
                self.peer.close()
            self.peer = peer
        try:
            with sock.makefile('rb') as reader:
                while True:
                    line = reader.readline(LIMIT + 1)
                    if not line:
                        break
                    if len(line) > LIMIT:
                        break
                    self.receive(peer, json.loads(line))
        except (OSError, ValueError):
            pass
        finally:
            with self.lock:
                if self.peer is peer:
                    self.peer = None
            peer.close()


def daemon(folder, command, runtime='codex'):
    lock = open(folder / 'daemon.lock', 'a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    path = folder / 'socket'
    path.unlink(missing_ok=True)
    listener = socket.socket(socket.AF_UNIX)
    listener.bind(str(path))
    listener.listen(4)
    (folder / 'daemon.pid').write_text(str(os.getpid()))
    host = (CliHost(folder, command, runtime) if runtime in ('dsh', 'claude') else
            OpenCodeHost(folder, command) if runtime == 'opencode' else Host(folder, command))
    while True:
        sock, _ = listener.accept()
        threading.Thread(target=host.attach, args=(sock,), daemon=True).start()


class OpenCodeHost:
    """Runtime adapter: exposes the codex app-server surface backed by `opencode serve`.

    Phone-facing protocol stays the codex JSON-RPC the app speaks; every request
    is translated to the OpenCode v2 HTTP API, and /api/event SSE is translated
    back into codex item/turn events. Session folders are runtime-scoped, so
    codex and opencode daemons never collide.
    """

    def __init__(self, folder, command):
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.folder = folder
        self.lock = threading.RLock()
        self.peer = None
        self.shutting_down = False
        self.initialized = False
        self.last_thread = ''
        self.active = set()
        self.text_parts = {}
        self.reason_parts = {}
        self.last_prompt = {}
        self.permissions = {}
        self.base = None
        self.port = 4096
        import re as _re
        match = _re.search(r'--port[= ](\d+)', command)
        if match:
            self.port = int(match.group(1))
        self.process = subprocess.Popen(command, shell=True, executable='/bin/sh',
                                        stdin=subprocess.DEVNULL, stdout=sys.stderr,
                                        stderr=sys.stderr, start_new_session=True,
                                        env=proxy_env() or None)
        (folder / 'server.pid').write_text(str(self.process.pid))
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                self.http('GET', '/global/health', timeout=3)
                break
            except Exception:
                time.sleep(0.5)
        self.base = 'http://127.0.0.1:%d' % self.port
        threading.Thread(target=self.event_loop, daemon=True).start()

    # ---------- transport ----------
    def http(self, method, path, body=None, timeout=40):
        request = urllib.request.Request(self.base_for_call() + path, method=method)
        request.add_header('Content-Type', 'application/json')
        data = json.dumps(body).encode() if body is not None else None
        with urllib.request.urlopen(request, data=data, timeout=timeout) as response:
            payload = response.read().decode('utf-8', 'replace')
            return json.loads(payload) if payload.strip() else {}

    def base_for_call(self):
        return self.base if self.base else 'http://127.0.0.1:%d' % self.port

    def reply(self, peer, ident, result):
        if ident is not None:
            peer.send(dict(id=ident, result=result))

    def push(self, value):
        with self.lock:
            if self.peer is not None and not self.shutting_down:
                self.peer.send(value)

    # ---------- SSE ----------
    def event_loop(self):
        while True:
            try:
                self.event_cycle()
            except Exception:
                pass
            time.sleep(3)

    def event_cycle(self):
        request = urllib.request.Request(self.base_for_call() + '/api/event?follow=true')
        with urllib.request.urlopen(request, timeout=120) as stream:
            for raw in stream:
                line = raw.decode('utf-8', 'replace').strip()
                if not line.startswith('data: '):
                    continue
                try:
                    event = json.loads(line[6:])
                except ValueError:
                    continue
                etype = event.get('type') or event.get('event') or ''
                data = event.get('data') or event.get('properties') or {}
                if not isinstance(data, dict):
                    continue
                for outgoing in self.translate(etype, data):
                    self.push(outgoing)

    def translate(self, etype, d):
        sid = d.get('sessionID', '')
        out = []

        def emit(method, **params):
            out.append(dict(method=method, params=params))

        if etype == 'session.next.prompted':
            self.last_prompt[sid] = d.get('messageID', '')
            self.active.add(sid)
            emit('turn/started', threadId=sid, turn={'id': d.get('messageID', '')})
            emit('thread/status/changed', threadId=sid, status={'type': 'active'})
        elif etype == 'session.next.text.started':
            part = d.get('textID', '')
            self.text_parts[part] = ''
            emit('item/started', threadId=sid, itemId=part,
                 item={'id': part, 'type': 'agentMessage', 'text': ''})
        elif etype == 'session.next.text.delta':
            part = d.get('textID', '')
            self.text_parts[part] = self.text_parts.get(part, '') + d.get('delta', '')
            emit('item/agentMessage/delta', threadId=sid, itemId=part, delta=d.get('delta', ''))
        elif etype == 'session.next.text.ended':
            part = d.get('textID', '')
            emit('item/completed', threadId=sid,
                 item={'id': part, 'type': 'agentMessage',
                       'text': d.get('text') or self.text_parts.pop(part, '')})
        elif etype == 'session.next.reasoning.started':
            part = d.get('reasoningID', '')
            self.reason_parts[part] = ''
            emit('item/started', threadId=sid, itemId=part,
                 item={'id': part, 'type': 'reasoning', 'text': '', 'status': 'inProgress'})
        elif etype == 'session.next.reasoning.delta':
            emit('item/reasoning/textDelta', threadId=sid,
                 itemId=d.get('reasoningID', ''), delta=d.get('delta', ''))
        elif etype == 'session.next.reasoning.ended':
            emit('item/completed', threadId=sid,
                 item={'id': d.get('reasoningID', ''), 'type': 'reasoning', 'text': d.get('text', '')})
        elif etype == 'session.next.tool.input.started':
            call = d.get('callID', '')
            emit('item/started', threadId=sid, itemId=call,
                 item={'id': call, 'type': 'commandExecution', 'command': d.get('name', ''),
                       'text': '', 'status': 'inProgress'})
        elif etype == 'session.next.tool.input.delta':
            emit('item/commandExecution/outputDelta', threadId=sid,
                 itemId=d.get('callID', ''), delta=d.get('delta', ''))
        elif etype in ('session.next.tool.success', 'session.next.tool.failed'):
            call = d.get('callID', '')
            ok = etype.endswith('success')
            emit('item/completed', threadId=sid,
                 item={'id': call, 'type': 'commandExecution', 'command': '',
                       'status': 'completed' if ok else 'failed', 'exitCode': 0 if ok else 1})
        elif etype == 'session.next.step.failed':
            self.active.discard(sid)
            emit('turn/completed', threadId=sid,
                 turn={'id': self.last_prompt.get(sid, ''), 'status': 'failed'})
            emit('thread/status/changed', threadId=sid, status={'type': 'idle'})
        elif etype == 'session.idle':
            if sid in self.active:
                self.active.discard(sid)
                emit('turn/completed', threadId=sid,
                     turn={'id': self.last_prompt.get(sid, ''), 'status': 'completed'})
                emit('thread/status/changed', threadId=sid, status={'type': 'idle'})
        elif etype == 'session.error':
            message = d.get('message') or d.get('error') or 'OpenCode 报告错误'
            emit('warning', message=str(message))
        elif etype == 'session.diff':
            emit('turn/diff/updated', threadId=sid,
                 turnId=self.last_prompt.get(sid, ''), diff=d.get('diff', ''))
        elif etype == 'permission.v2.asked':
            request_id = d.get('id', '')
            if request_id:
                self.permissions[request_id] = sid
                resources = d.get('resources') or []
                summary = ' '.join(str(r) for r in resources[:2]) if resources else d.get('action', '')
                out.append(dict(id='ocp-' + request_id,
                                method='item/commandExecution/requestApproval',
                                params={'threadId': sid,
                                        'request': {'command': str(d.get('action', '')) + ' ' + str(summary)[:200]}}))
        return out

    # ---------- phone-facing dispatch ----------
    def receive(self, peer, value):
        with self.lock:
            if peer is not self.peer:
                return
        method = value.get('method')
        ident = value.get('id')
        params = value.get('params') or {}
        try:
            if self.shutting_down:
                if ident is not None:
                    peer.send(dict(id=ident, error=dict(code=-32000, message='远端正在关闭')))
                return
            if method == 'initialize':
                self.initialized = True
                self.reply(peer, ident, {'userAgent': 'lan-opencode-adapter/1.0',
                                         'codexHome': str(Path.home() / '.opencode'),
                                         'platformFamily': 'unix', 'platformOs': 'linux'})
                return
            if method == 'initialized':
                return
            if method == 'account/read':
                self.reply(peer, ident, {'account': {'type': 'opencode'}, 'requiresOpenaiAuth': False})
                return
            if method == 'account/rateLimits/read':
                self.reply(peer, ident, {})
                return
            if method == 'remote/session':
                self.reply(peer, ident, {'lastThread': self.last_thread, 'threads': [],
                                         'multiSession': True, 'safeRestart': False,
                                         'sessionFolder': self.folder.name,
                                         'scriptVersion': SCRIPT_VERSION,
                                         'capabilities': {'runtime': 'opencode', 'models': True,
                                                          'attachments': False, 'approval': True,
                                                          'resume': True, 'interrupt': True}})
                return
            if method == 'remote/replay':
                self.reply(peer, ident, {})
                return
            if method == 'remote/shutdownIfIdle':
                if self.active or self.permissions:
                    peer.send(dict(id=ident, error=dict(code=-32000, message='远端仍有任务，未执行重启')))
                else:
                    self.shutting_down = True
                    peer.send(dict(id=ident, result={'shutdown': True}))

                    def stop():
                        try:
                            os.killpg(self.process.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                    threading.Timer(1.0, stop).start()
                return
            if method == 'thread/list':
                sessions = self.http('GET', '/api/session').get('data', [])
                threads = []
                for s in sessions:
                    if not isinstance(s, dict):
                        continue
                    threads.append({'id': s.get('id', ''),
                                    'name': s.get('title') or s.get('name') or '',
                                    'cwd': (s.get('location') or {}).get('directory', ''),
                                    'status': {'type': 'active' if s.get('id') in self.active else 'idle'},
                                    'updatedAt': int((s.get('time') or {}).get('updated') or 0)})
                self.reply(peer, ident, {'data': threads})
                return
            if method == 'thread/start':
                body = {'location': {'directory': params.get('cwd') or str(Path.home())}}
                created = self.http('POST', '/api/session', body).get('data', {})
                thread_id = created.get('id', '')
                self.last_thread = thread_id
                self.reply(peer, ident, {'thread': {'id': thread_id,
                                                    'cwd': (created.get('location') or {}).get('directory', ''),
                                                    'status': {'type': 'idle'}}})
                return
            if method in ('thread/read', 'thread/resume'):
                thread_id = params.get('threadId', '')
                self.last_thread = thread_id
                turns = self.history_turns(thread_id)
                self.reply(peer, ident, {'thread': {'id': thread_id,
                                                    'status': {'type': 'active' if thread_id in self.active else 'idle'}},
                                         'turns': turns})
                return
            if method == 'turn/start':
                thread_id = params.get('threadId', '')
                text = ''
                for entry in params.get('input', []):
                    if isinstance(entry, dict) and entry.get('type') == 'text':
                        text += entry.get('text', '')
                body = {'prompt': {'text': text}}
                model = params.get('model') or ''
                if '/' in model:
                    provider, model_id = model.split('/', 1)
                    body['model'] = {'id': model_id, 'providerID': provider}
                result = self.http('POST', '/api/session/%s/prompt' % thread_id, body).get('data', {})
                self.last_prompt[thread_id] = result.get('id', '')
                self.active.add(thread_id)
                self.reply(peer, ident, {'turn': {'id': result.get('id', ''), 'status': 'inProgress'}})
                return
            if method == 'turn/interrupt':
                self.http('POST', '/api/session/%s/interrupt' % params.get('threadId', ''), {})
                self.reply(peer, ident, {'interrupted': True})
                return
            if method == 'model/list':
                models = []
                for m in self.http('GET', '/api/model').get('data', []):
                    if not isinstance(m, dict) or not m.get('id'):
                        continue
                    models.append({'id': '%s/%s' % (m.get('providerID', ''), m.get('id')),
                                   'name': m.get('name') or m.get('id'),
                                   'description': m.get('reasoning') and '推理' or ''})
                self.reply(peer, ident, {'data': models, 'nextCursor': None})
                return
            if not method and ident is not None:
                request_id = str(ident)
                for prefix in ('ocp-', ''):
                    key = request_id[len(prefix):] if request_id.startswith(prefix) else None
                    if key and key in self.permissions:
                        session = self.permissions.pop(key)
                        decision = (value.get('result') or {}).get('decision', 'decline')
                        reply = 'once' if decision in ('accept', 'acceptForSession') else 'reject'
                        self.http('POST', '/api/session/%s/permission/%s/reply' % (session, key),
                                  {'reply': reply})
                        return
                return
            if ident is not None:
                peer.send(dict(id=ident, error=dict(code=-32601, message='opencode 适配不支持 ' + str(method))))
        except Exception as e:
            if ident is not None:
                peer.send(dict(id=ident, error=dict(code=-32000, message='opencode 适配错误: %s' % e)))

    def history_turns(self, thread_id):
        turns = []
        try:
            messages = self.http('GET', '/api/session/%s/message' % thread_id).get('data', [])
        except Exception:
            messages = []
        for m in messages:
            if not isinstance(m, dict):
                continue
            info = m.get('info') if isinstance(m.get('info'), dict) else m
            parts = m.get('parts') or []
            role = info.get('role', '')
            mid = info.get('id', '')
            if role == 'user':
                text = ' '.join(str(part.get('text', '')) for part in parts
                                if isinstance(part, dict) and part.get('type') == 'text')
                turns.append({'id': mid, 'status': 'completed',
                              'items': [{'id': mid + '-u', 'type': 'userMessage', 'text': text}]})
            else:
                items = []
                for part in parts:
                    if not isinstance(part, dict):
                        continue
                    if part.get('type') == 'text':
                        items.append({'id': part.get('id', mid + '-t'), 'type': 'agentMessage',
                                      'text': part.get('text', '')})
                    elif part.get('type') == 'reasoning':
                        items.append({'id': part.get('id', mid + '-r'), 'type': 'reasoning',
                                      'text': part.get('text', '')})
                    elif part.get('type') == 'tool':
                        items.append({'id': part.get('id', mid + '-c'), 'type': 'commandExecution',
                                      'command': part.get('tool', ''),
                                      'state': {'status': 'completed'}})
                if items:
                    turns.append({'id': mid, 'status': 'completed', 'items': items})
        return turns

    def attach(self, sock):
        peer = Peer(sock)
        with self.lock:
            if self.peer:
                self.peer.close()
            self.peer = peer
        try:
            with sock.makefile('rb') as reader:
                while True:
                    line = reader.readline(LIMIT + 1)
                    if not line or len(line) > LIMIT:
                        break
                    self.receive(peer, json.loads(line))
        except (OSError, ValueError):
            pass
        finally:
            with self.lock:
                if self.peer is peer:
                    self.peer = None
            peer.close()


def connect_socket(folder):
    sock = socket.socket(socket.AF_UNIX)
    try:
        sock.connect(str(folder / 'socket'))
        return sock
    except OSError:
        sock.close()
        return None


def spawn_daemon(folder, command, runtime='codex'):
    with open(folder / 'daemon.log', 'ab', buffering=0) as log:
        subprocess.Popen([sys.executable, __file__, '--daemon', str(folder), command, runtime],
                         stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                         start_new_session=True, close_fds=True)


def wait_socket(folder, attempts=100):
    for _ in range(attempts):
        sock = connect_socket(folder)
        if sock is not None:
            return sock
        time.sleep(.1)
    return None


def rpc_once(sock, request, timeout=4.0):
    """One line-delimited JSON-RPC round trip, skipping unrelated events."""
    sock.settimeout(timeout)
    sock.sendall(encode(request))
    buffer = b''
    deadline = time.time() + timeout
    while time.time() < deadline:
        while b'\n' not in buffer:
            chunk = sock.recv(65536)
            if not chunk:
                raise OSError('eof')
            buffer += chunk
        line, _, buffer = buffer.partition(b'\n')
        message = json.loads(line)
        if message.get('id') == request.get('id'):
            return message
    return None


def wait_pid_exit(folder, timeout=8.0):
    try:
        pid = int((folder / 'daemon.pid').read_text())
    except (OSError, ValueError):
        return
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
        except OSError:
            return
        time.sleep(.1)


def upgrade_if_stale(sock, folder, command, runtime='codex'):
    """An app upgrade must not keep talking to a daemon running old bridge code.

    Probe the live daemon's script version; when it differs and no task is
    running, shut it down safely and start a replacement with this script.
    A busy daemon is kept so in-flight work survives; new host features then
    appear after its next natural restart.
    """
    try:
        probe = rpc_once(sock, dict(id='upgrade-probe', method='remote/session', params={}))
        version = ((probe or {}).get('result') or {}).get('scriptVersion')
        if version == SCRIPT_VERSION:
            return sock
        stop = rpc_once(sock, dict(id='upgrade-stop', method='remote/shutdownIfIdle', params={}))
        granted = isinstance(stop, dict) and (stop.get('result') or {}).get('shutdown')
    except (OSError, json.JSONDecodeError):
        try:
            sock.close()
        except OSError:
            pass
        wait_pid_exit(folder)
        replacement = connect_socket(folder)
        if replacement is None:
            spawn_daemon(folder, command)
            replacement = wait_socket(folder)
            if replacement is None:
                raise RuntimeError('远端会话服务升级失败，请查看 ' + str(folder / 'daemon.log'))
        return replacement
    if granted:
        try:
            sock.close()
        except OSError:
            pass
        wait_pid_exit(folder)
        spawn_daemon(folder, command, runtime)
        replacement = wait_socket(folder)
        if replacement is None:
            raise RuntimeError('远端会话服务升级失败，请查看 ' + str(folder / 'daemon.log'))
        return replacement
    result = (probe or {}).get('result') or {}
    if not result.get('lastThread') and not result.get('threads'):
        # An old daemon that never hosted a session refuses shutdownIfIdle only
        # because it is uninitialized; it owns nothing. Kill its process group
        # (daemon + its idle app-server child) and start the upgraded script.
        try:
            sock.close()
        except OSError:
            pass
        try:
            pid = int((folder / 'daemon.pid').read_text())
            try:
                os.killpg(pid, signal.SIGTERM)
            except OSError:
                os.kill(pid, signal.SIGTERM)
        except (OSError, ValueError):
            pass
        wait_pid_exit(folder)
        spawn_daemon(folder, command, runtime)
        replacement = wait_socket(folder)
        if replacement is None:
            raise RuntimeError('远端会话服务升级失败，请查看 ' + str(folder / 'daemon.log'))
        return replacement
    return sock


def attach(folder, command, runtime='codex'):
    # Serialize check/start; concurrent reconnects cannot start duplicate servers.
    with open(folder / 'launch.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        sock = connect_socket(folder)
        if sock is None:
            spawn_daemon(folder, command, runtime)
            sock = wait_socket(folder)
            if sock is None:
                raise RuntimeError('远端会话启动失败，请查看 ' + str(folder / 'daemon.log'))
        else:
            sock = upgrade_if_stale(sock, folder, command, runtime)
    # Probe deadlines must never become an idle timeout for the live tunnel.
    sock.settimeout(None)
    def receive():
        try:
            while True:
                data = sock.recv(65536)
                if not data:
                    break
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
        finally:
            os._exit(0)
    threading.Thread(target=receive, daemon=True).start()
    try:
        while True:
            data = os.read(sys.stdin.fileno(), 65536)
            if not data:
                break
            sock.sendall(data)
    finally:
        sock.close()


def live_daemon(folder):
    """Check an existing daemon without attaching and evicting its phone peer."""
    try:
        pid = int((folder / 'daemon.pid').read_text())
        os.kill(pid, 0)
        return (folder / 'socket').exists()
    except (OSError, ValueError):
        return False


def session_folder(identity_argument, command, runtime='codex'):
    """Share one app-server across network aliases of the same remote account."""
    root = Path.home() / '.remote-gpt'
    try:
        decoded = json.loads(identity_argument)
        identities = decoded if isinstance(decoded, list) else [identity_argument]
    except (json.JSONDecodeError, TypeError):
        identities = [identity_argument]

    # The home directory already scopes this to one remote OS account. The
    # command remains part of the key so intentionally different app-server
    # launches stay isolated, while Wi-Fi/Tailscale host aliases converge.
    # lan-v1 namespace: the upstream official app shares this PC with the same
    # command string; one daemon serves ONE client app (a new attach evicts the
    # previous peer). A separate namespace gives this app its own daemon and
    # app-server so the two apps stop evicting each other's connections.
    shared_key = hashlib.sha256(('lan-v1\0' + runtime + '\0' + command).encode()).hexdigest()[:24]
    shared = root / shared_key

    # Before 1.0.23 the profile UUID was part of the key. Reuse a live legacy
    # daemon before the shared one. A first failed post-upgrade connection may
    # already have created the shared daemon while the old Wi-Fi daemon still
    # owns an in-flight thread; preferring shared here would preserve the writer
    # conflict forever.
    for identity in identities:
        if not isinstance(identity, str) or not identity:
            continue
        legacy_key = hashlib.sha256((identity + '\0' + command).encode()).hexdigest()[:24]
        legacy = root / legacy_key
        if live_daemon(legacy):
            return legacy
    if live_daemon(shared):
        return shared
    return shared


if __name__ == '__main__':
    os.umask(0o077)
    if sys.argv[1] == '--daemon':
        daemon(Path(sys.argv[2]), sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else 'codex')
    else:
        identities, command = sys.argv[1:3]
        runtime = sys.argv[3] if len(sys.argv) > 3 else 'codex'
        folder = session_folder(identities, command, runtime)
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        attach(folder, command, runtime)
