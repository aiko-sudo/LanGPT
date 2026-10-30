"""Real socketpair checks for Peer writer deadlines without reader mode mutation."""
import importlib.util,json,socket,threading,time,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('peer_bridge',Path(__file__).resolve().parents[1]/'app/src/main/assets/remote_session.py')
bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
class WatchSocket:
 def __init__(self,s):self.raw=s;self.mutations=[]
 def __getattr__(self,name):return getattr(self.raw,name)
 def settimeout(self,value):self.mutations.append(('timeout',value));return self.raw.settimeout(value)
 def setblocking(self,value):self.mutations.append(('blocking',value));return self.raw.setblocking(value)
class PeerSocketTests(unittest.TestCase):
 def pair(self):
  a,b=socket.socketpair();a.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,4096)
  watched=WatchSocket(a);p=bridge.Peer(watched)
  self.addCleanup(b.close);self.addCleanup(p.close)
  return watched,b,p
 def test_roundtrip_preserves_blocking_reader(self):
  a,b,p=self.pair();p.send({'hello':'world'});b.settimeout(2)
  data=b''
  while not data.endswith(b'\n'):data+=b.recv(4096)
  self.assertEqual(json.loads(data),{'hello':'world'})
  b.sendall(b'phone-request\n');self.assertEqual(a.recv(4096),b'phone-request\n')
  self.assertIsNone(a.gettimeout());self.assertEqual(a.mutations,[])
 def test_slow_reader_does_not_change_concurrent_recv_mode(self):
  a,b,p=self.pair();p.send({'payload':'x'*(2*1024*1024)})
  time.sleep(.1);self.assertIsNone(a.gettimeout());self.assertEqual(a.mutations,[])
  b.sendall(b'concurrent-reader\n');self.assertEqual(a.recv(4096),b'concurrent-reader\n')
  self.assertTrue(p.alive)
 def test_nonreading_peer_fails_within_deadline(self):
  a,b,p=self.pair();p.send({'payload':'x'*(2*1024*1024)})
  deadline=time.monotonic()+12
  while p.alive and time.monotonic()<deadline:time.sleep(.05)
  self.assertFalse(p.alive);self.assertEqual(a.mutations,[])
 def test_idle_peer_has_no_reader_idle_timeout(self):
  a,b,p=self.pair();time.sleep(.1)
  self.assertTrue(p.alive);self.assertIsNone(a.gettimeout());self.assertEqual(a.mutations,[])
if __name__=='__main__':unittest.main()
