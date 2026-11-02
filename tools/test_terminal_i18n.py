import re, unittest, xml.etree.ElementTree as ET
from pathlib import Path
P=Path(__file__).resolve().parents[1]/'app/src/main'; J=P/'java/app/remotessh'
class Resources(unittest.TestCase):
 def test_keys_and_format(self):
  def read(loc):
   root=ET.parse(P/'res'/loc/'strings-terminal.xml').getroot(); items=[(e.attrib['name'],e.text or '') for e in root]; self.assertEqual(len(items),len(dict(items))); return dict(items)
  a,b=read('values'),read('values-en'); self.assertEqual(a.keys(),b.keys())
  for k in a: self.assertEqual(sorted(re.findall(r'%\d+\$[a-z]',a[k])),sorted(re.findall(r'%\d+\$[a-z]',b[k])),k)
  for f in ['SshTerminalActivity','LiveStatusNotifications','ConnectionService','BubbleActivity']:
   for key in re.findall(r'R.string.([a-zA-Z_0-9]+)',(J/(f+'.java')).read_text()):
    if key.startswith(('term_','live_','conn_','bubble_')): self.assertIn(key,a)
 def test_security_and_terminal_contract(self):
  s=(J/'SshTerminalActivity.java').read_text()
  for text in ['super.attachBaseContext(AppLanguage.wrap(base))','"StrictHostKeyChecking", "yes"','trimmed.equals("退出")','TerminalCommand.parse("ssh " + target','setPtyType("xterm-256color")']:
   self.assertIn(text,s)
  s=(J/'LiveStatusNotifications.java').read_text(); self.assertIn('app.state.startsWith("正在停止")',s)
if __name__=='__main__': unittest.main()
