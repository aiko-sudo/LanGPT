"""V1.2.12 regressions: Python preview tokenizer + background reconnect state machine.

Part A compiles the real PythonHighlight (tokenize has no Android deps; android.jar
only satisfies imports at compile time). Part B extracts the production
watchConnection body into a stub and drives the state machine deterministically.
"""
from pathlib import Path
import subprocess, tempfile, os
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
ANDROID=Path(testdeps.require(testdeps.android_jar(),'android'))
watch=method((SRC/'ConnectionService.java').read_text(),'  private void watchConnection()')

with tempfile.TemporaryDirectory(prefix='v1212-', ignore_cleanup_errors=True) as d:
    stub=Path(d)/'KeepaliveStub.java'
    stub.write_text('''package app.remotessh;
class Profile {}
class RemoteApp {
  boolean connected,connecting,userDisconnected;Profile profile;
  int connects;
  void connect(Profile p){connects++;}
}
public class KeepaliveStub {
  boolean wasConnected;int reconnectAttempts;long nextReconnectAt;
  static final int MAX_RECONNECT=6;
  static final long[] BACKOFF_MS={15000,30000,60000,120000,300000,300000};
  long now=0;RemoteApp app=new RemoteApp();
''' + watch.replace('System.currentTimeMillis()','this.now') + '''
  void tick(long step){now+=step;watchConnection();}
}
''')
    driver=Path(d)/'KeepaliveTest.java'
    driver.write_text('''package app.remotessh;
public class KeepaliveTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[] a){
  KeepaliveStub s=new KeepaliveStub();
  s.tick(5000);check(s.app.connects==0,"never-connected service stays idle");
  s.app.profile=new Profile();
  s.app.connected=true;s.tick(5000);s.app.connected=false;
  s.tick(5000);check(s.app.connects==0,"drop schedules backoff, no instant connect");
  s.tick(20000);check(s.app.connects==1,"first reconnect fires after 15s backoff");
  check(s.reconnectAttempts==1,"attempt counted");
  s.tick(35000);check(s.app.connects==2,"second reconnect after 30s backoff");
  s.tick(65000);check(s.app.connects==3,"third reconnect after 60s backoff");
  s.tick(130000);check(s.app.connects==4,"fourth reconnect after 120s backoff");
  s.tick(310000);check(s.app.connects==5,"fifth reconnect after 300s backoff");
  s.tick(310000);check(s.app.connects==6,"sixth reconnect after 300s backoff");
  s.tick(310000);check(s.app.connects==6 && s.reconnectAttempts==6,"budget exhausted, no further attempts");
  s.app.connecting=true;s.tick(0);s.app.connecting=false;
  s.app.profile=null;s.tick(0);check(s.app.connects==6,"no profile means no reconnect");
  s.app.profile=new Profile();s.app.connected=true;s.tick(5000);
  s.app.connected=false;s.app.userDisconnected=true;s.tick(310000);
  check(s.app.connects==6,"user disconnect never auto-reconnects");
  check(!s.wasConnected,"user disconnect retires the watcher");
  s.app.userDisconnected=false;s.tick(0);check(s.app.connects==6,"retired watcher stays idle until next success");
  s.app.connected=true;s.tick(5000);s.app.connected=false;
  s.tick(310000);s.tick(310000);check(s.app.connects==7,"fresh connection restarts a full budget");
 }
}
''')
    subprocess.run(['javac','-cp',str(ANDROID),'-d',d,str(stub),str(driver)],check=True)
    subprocess.run(['java','-cp',d,'app.remotessh.KeepaliveTest'],check=True,timeout=30)

    py=Path(d)/'PyHighlightTest.java'
    py.write_text('''package app.remotessh;
import java.util.*;
public class PyHighlightTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 static List<String> spans(String src,int only){
  List<String> out=new ArrayList<>();
  for(PythonHighlight.Token t:PythonHighlight.tokenize(src)) if(t.kind==only) out.add(src.substring(t.start,t.end));
  return out;
 }
 public static void main(String[] a){
  check(PythonHighlight.isPython("/a/b/Main.PY")&&!PythonHighlight.isPython("/a/b/pypi.txt"),"isPython matches extension only");
  String TQ="\\"\\"\\"";
  StringBuilder b=new StringBuilder();
  b.append("# note\\n");
  b.append("def greet(name):\\n");
  b.append("  ").append(TQ).append("Doc goes \\"here\\" and across\\n lines").append(TQ).append("\\n");
  b.append("  x=0x1F_2\\n");
  b.append("  y=1_000.5e-3\\n");
  b.append("  print(f\\"hi {name}\\")\\n");
  b.append("  return self.data\\n");
  String src=b.toString();
  List<PythonHighlight.Token> ts=PythonHighlight.tokenize(src);
  for(int i=1;i<ts.size();i++)check(ts.get(i).start>=ts.get(i-1).end,"tokens ordered and non-overlapping "+i);
  check(spans(src,PythonHighlight.COMMENT).equals(List.of("# note")),"full-line comment");
  List<String> strs=spans(src,PythonHighlight.STRING);
  check(strs.size()>=2,"docstring and f-string found: "+strs);
  check(strs.get(0).startsWith(TQ)&&strs.get(0).contains("\\n"),"triple-quoted docstring spans lines");
  check(strs.get(strs.size()-1).equals("\\"hi {name}\\""),"f-string literal highlighted");
  check(spans(src,PythonHighlight.DEFNAME).equals(List.of("greet")),"def name highlighted");
  check(spans(src,PythonHighlight.KEYWORD).containsAll(List.of("def","return")),"keywords def/return");
  check(spans(src,PythonHighlight.SELF).equals(List.of("self")),"self highlighted");
  check(spans(src,PythonHighlight.BUILTIN).contains("print"),"builtin print");
  check(spans(src,PythonHighlight.NUMBER).equals(List.of("0x1F_2","1_000.5e-3")),"hex/underscore/exponent numbers");
  String deco="@app.route(\\"/x\\")\\ndef v():pass\\n";
  check(spans(deco,PythonHighlight.DECORATOR).equals(List.of("@app.route")),"decorator with dotted name");
  String unclosed="s=\\"abc\\n";check(spans(unclosed,PythonHighlight.STRING).equals(List.of("\\"abc")),"unterminated string stops at newline");
  check(PythonHighlight.tokenize("").isEmpty()&&PythonHighlight.tokenize(null).isEmpty(),"empty input safe");
  check(spans("class A(B): pass",PythonHighlight.DEFNAME).equals(List.of("A")),"class name only, base not highlighted");
 }
}
''')
    subprocess.run(['javac','-cp',str(ANDROID),'-d',d,str(SRC/'PythonHighlight.java'),str(py)],check=True)
    subprocess.run(['java','-cp',d+os.pathsep+str(ANDROID),'app.remotessh.PyHighlightTest'],check=True,timeout=30)
print('V1.2.12 regressions passed; on-device colour rendering and background reconnect still need phone verification.')
