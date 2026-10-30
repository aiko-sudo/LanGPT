"""V1.2.13 regression: abandoned thread shells never surface as 未命名任务.

Extracts the production workConversations() and drives it with stub states:
 1. title/items/approval/error-empty, idle shell  -> hidden from the panel
 2. shell with remote running                     -> still shown (active remote work)
 3. shell with an error                           -> still shown (user must see failures)
 4. normal titled task                            -> shown
Also locks the request()-side rules by source checks:
 5. thread/read not-loaded during background restore keeps local data and advances the queue
 6. explicit open (loadedThreads) keeps the friendly banner path
 7. restore purges only when thread.turns exists and is empty (missing turns = unknown, keep)
"""
from pathlib import Path
import subprocess, tempfile, os, re
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))
source=(SRC/'RemoteApp.java').read_text()
work=method(source,'  List<ConversationState> workConversations()')

with tempfile.TemporaryDirectory(prefix='shell-', ignore_cleanup_errors=True) as d:
    java=Path(d)/'ShellTaskTest.java'
    java.write_text('''package app.remotessh;
import java.util.*;import org.json.*;
class ConversationStore {
  LinkedHashMap<String,ConversationState> map=new LinkedHashMap<>();
  Collection<ConversationState> values(){return map.values();}
  ConversationState get(String id){return map.get(id);}
  void remove(String id){map.remove(id);}
  void select(String id){}
}
class RemoteApp {
  final ConversationStore conversationStore=new ConversationStore();
  final org.json.JSONArray workThreads=new org.json.JSONArray();
  final org.json.JSONArray threads=new org.json.JSONArray();
  final Set<String> deletedThreadIds=new HashSet<>();
  void checkpoint(){}
  boolean threadRunning(JSONObject t){return "active".equals(Json.object(t,"status").optString("type"));}
''' + work + '''
}
class Json {
 static JSONObject obj(Object... kv){JSONObject j=new JSONObject();for(int i=0;i<kv.length;i+=2)j.put(kv[i].toString(),kv[i+1]);return j;}
 static JSONObject object(JSONObject src,String key){return src==null?new JSONObject():src.optJSONObject(key)==null?new JSONObject():src.optJSONObject(key);}
}
class ConversationState {
  String threadId="",title="",cwd="",error="";
  boolean remoteRunning,busy,readOnly;
  final List<Object> items=new ArrayList<>();
  final List<JSONObject> approvals=new ArrayList<>();
}
public class ShellTaskTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 static ConversationState state(RemoteApp a,String id,String title,boolean running,String error){
  ConversationState c=new ConversationState();c.threadId=id;c.title=title;c.remoteRunning=running;c.error=error;
  a.conversationStore.map.put(id,c);return c;
 }
 public static void main(String[] args){
  RemoteApp app=new RemoteApp();
  state(app,"shell","",false,"");
  state(app,"running","",true,"");
  ConversationState failed=state(app,"failed","",false,"发送失败");
  state(app,"normal","重构计划",false,"");
  List<ConversationState> out=app.workConversations();
  Set<String> ids=new HashSet<>();
  for(ConversationState c:out)ids.add(c.threadId);
  check(!ids.contains("shell"),"idle empty shell hidden from work panel");
  check(ids.contains("running"),"running remote thread still shown");
  check(ids.contains("failed"),"shell with error still shown");
  check(ids.contains("normal"),"normal titled task shown");
  failed.approvals.add(new JSONObject());
  ids.clear();for(ConversationState c:app.workConversations())ids.add(c.threadId);
  check(ids.contains("failed"),"shell awaiting approval still shown");
  app.deletedThreadIds.add("running");
  ids.clear();for(ConversationState c:app.workConversations())ids.add(c.threadId);
  check(!ids.contains("running"),"deleted threads stay hidden");
  System.out.println("TOTAL 7");
 }
}
''')
    subprocess.run(['javac','-cp',str(JAR),'-d',d,str(java)],check=True)
    subprocess.run(['java','-cp',d+os.pathsep+str(JAR),'app.remotessh.ShellTaskTest'],check=True,timeout=30)

# source-level contract checks for the request()/restoreNext() side
prod=(SRC/'RemoteApp.java').read_text()
checks=[
 ('restore purge on not-loaded','recoveringThreads.contains(requestThread) && !loadedThreads.contains(requestThread)'),
 ('not-loaded keeps local data','recoveringThreads.remove(requestThread)'),
 ('restore queue advances on not-loaded','restoringThread=""; main.post(this::restoreNext)'),
 ('turns read from thread object','turnsOut=head.optJSONArray("turns")'),
 ('missing turns array never purges','turnsOut!=null && turnsOut.length()==0'),
 ('explicit restore exempt','!id.equals(explicitlyRestoredThread)'),
 ('recovering set cleaned in purge','conversationLoads.remove(id);recoveringThreads.remove(id)'),
 ('friendly banner preserved','该会话未在电脑端加载'),
]
for name,needle in checks:
    assert needle in prod, name
    print('PASS',name)
print('shell-thread regression passed; device-side reconnection cycle still needs phone verification.')
