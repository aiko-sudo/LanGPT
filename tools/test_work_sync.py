"""Exercise production startup selection, delete cleanup and work refresh callbacks on JVM.
Transport/store adapters isolate the protocol side effects; no real delete/SSH requests.
"""
from pathlib import Path
import subprocess
import tempfile
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))

def main():
    source=(SRC/'RemoteApp.java').read_text()
    methods='\n'.join(method(source,s) for s in ['  private Profile startupProfile()',
      '  void connectStartupOnce()', '  void deleteThread(',      '  void refreshWorkPanel(', '  List<ConversationState> workConversations()',
      '  static boolean isThreadNotLoaded(', '  static boolean isRemoteMissingThread(', '  private void purgeDeletedThread('])
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='work-sync-test-', ignore_cleanup_errors=True) as directory:
        directory=Path(directory);test=directory/'WorkSyncTest.java'
        test.write_text('''package app.remotessh;
import java.util.*;import java.util.function.*;import org.json.*;
class Profile {String id,lastThread="";Set<String> projects=new HashSet<>();Profile(String id){this.id=id;}}
class Vault {List<Profile> profiles=new ArrayList<>();}
class Preferences {String last="";Map<String,Set<String>> sets=new HashMap<>();
 String getString(String name,String fallback){return last;}
 Set<String> getStringSet(String key,Set<String> fallback){Set<String> v=sets.get(key);return v==null?fallback:v;}
 void setts(Map<String,Set<String>> m){sets.putAll(m);}
 Editor edit(){Editor e=new Editor();e.p=this;return e;}}
class Editor {Preferences p;Map<String,Set<String>> pending=new HashMap<>();
 Editor putStringSet(String key,Set<String> v){pending.put(key,v);return this;}
  void apply(){p.setts(pending);}}
class RpcException extends java.io.IOException {public final int code;public RpcException(int c,String m){super(m);code=c;}}
class ConnectionFailure {static Throwable unwrap(Throwable t){return t;}}
class ConversationState {String threadId="",cwd="",title="",error="";boolean remoteRunning,busy,readOnly;final java.util.List<Object> items=new java.util.ArrayList<>();final java.util.List<org.json.JSONObject> approvals=new java.util.ArrayList<>();}
class Store {
 Map<String,ConversationState> values=new LinkedHashMap<>();
 Collection<ConversationState> values(){return values.values();}ConversationState get(String id){return values.get(id);}void remove(String id){values.remove(id);}}
class Progress {void finish(long time){}}
class Inbox {Set<String> removed=new HashSet<>();void remove(String p,String id){removed.add(p+":"+id);}}
class Turns {String removed="";void forgetThread(String id){removed=id;}}
class App {
 static final int MODE_PRIVATE=0;boolean startupConnectionAttempted,connected,connecting,busy,remoteRunning;
 int connects,changes,workRefreshSequence;long statusRevision;
 Profile profile;Vault vault=new Vault();Preferences prefs=new Preferences();
 String threadId="",threadTitle="",restoringThread="",loadingThread="",pendingNavigation="";
 Set<String> deletedThreadIds=new HashSet<>(),loadedThreads=new HashSet<>();
 ArrayDeque<String> restoreQueue=new ArrayDeque<>();Map<String,String> conversationLoads=new HashMap<>();Set<String> recoveringThreads=new HashSet<>();
 Map<String,Progress> taskProgress=new HashMap<>();Inbox taskInbox=new Inbox();Turns turns=new Turns();
 JSONArray threads=new JSONArray(),workThreads=new JSONArray();Store conversationStore=new Store();
 List<Consumer<JSONObject>> successes=new ArrayList<>();List<Consumer<Throwable>> failures=new ArrayList<>();JSONObject requestParams;
 Preferences getSharedPreferences(String name,int mode){return prefs;}
 void connect(Profile p){connects++;profile=p;}void checkpoint(){}void changed(){changes++;}void save(){}
 void landHome(){threadId="";busy=false;remoteRunning=false;}
 boolean threadRunning(JSONObject thread){return Json.object(thread,"status").optString("type").equals("active");}
 Progress taskProgress(String id){return new Progress();}
 String restoredTitle(JSONObject json,String id,String title){return json.optString("name",title);}
 void request(String method,JSONObject params,Consumer<JSONObject> success,Consumer<Throwable> failure){requestParams=params;successes.add(success);failures.add(failure);}
 void inConversation(String id,Runnable action){
  if(deletedThreadIds.contains(id))return;
  String selected=threadId;boolean running=remoteRunning;
  threadId=id;action.run();ConversationState c=conversationStore.get(id);if(c!=null)c.remoteRunning=remoteRunning;
  threadId=selected;remoteRunning=running;
 }
''' + methods + '''
}
public class WorkSyncTest {
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS "+label);}
 static ConversationState task(String id,boolean running){ConversationState c=new ConversationState();c.threadId=id;c.cwd="/project";c.remoteRunning=running;return c;}
 static JSONObject listed(String id,String status){return Json.obj("id",id,"cwd","/project","status",Json.obj("type",status));}
 public static void main(String[]args){
 App s=new App();s.connectStartupOnce();check(s.connects==0,"no saved profile does not attempt connection");
 s=new App();s.vault.profiles.add(new Profile("first"));s.vault.profiles.add(new Profile("last"));s.prefs.last="last";
 s.connectStartupOnce();s.connectStartupOnce();check(s.connects==1 && s.profile.id.equals("last"),"startup connects last-used profile once even after failure/re-entry");
 App already=new App();already.connected=true;already.vault.profiles.add(new Profile("p"));already.connectStartupOnce();check(already.connects==0,"existing live connection not disrupted by startup");
 App a=new App();a.profile=new Profile("p");a.threadId="A";a.profile.lastThread="A";
 a.conversationStore.values.put("A",task("A",false));a.conversationStore.values.put("B",task("B",true));
 a.threads.put(listed("A","idle")).put(listed("B","active"));a.loadedThreads.add("A");a.restoreQueue.add("A");a.conversationLoads.put("A","loading");
 int[] done={0};a.deleteThread("A",error->{if(error!=null)throw new AssertionError(error);done[0]++;});
 check(a.conversationStore.get("A")!=null,"cache not removed before remote delete confirmation");a.successes.get(0).accept(new JSONObject());
 check(done[0]==1 && a.conversationStore.get("A")==null && a.conversationStore.get("B")!=null,"delete success synchronizes cache and preserves task B");
 check(a.threadId.isEmpty() && a.profile.lastThread.isEmpty() && !a.loadedThreads.contains("A") && !a.restoreQueue.contains("A"),"deleted selected thread lands home and removes restoration metadata");
 check(a.taskInbox.removed.contains("p:A") && a.turns.removed.equals("A"),"delete removes results and queued messages");
 a.workThreads.put(listed("A","active"));a.conversationStore.values.put("A",task("A",false));
 check(a.workConversations().stream().noneMatch(t->t.threadId.equals("A")),"late list/cached deleted task cannot reappear in work panel");
 App failed=new App();failed.profile=new Profile("p");failed.conversationStore.values.put("X",task("X",false));failed.deleteThread("X",error->{});failed.failures.get(0).accept(new Exception("fixture"));
 check(failed.conversationStore.get("X")!=null && failed.deletedThreadIds.isEmpty(),"failed remote deletion preserves all local data");
 App ghost=new App();ghost.profile=new Profile("p");ghost.conversationStore.values.put("G",task("G",false));
 ghost.prefs.sets.put("p",new HashSet<>(java.util.Set.of("G","K")));ghost.threads.put(listed("G","idle"));
 int[] gdone={0};ghost.deleteThread("G",error->{if(error!=null)throw new AssertionError(error);gdone[0]++;});
 ghost.failures.get(0).accept(new RpcException(-32600,"no rollout found for thread id G"));
 check(gdone[0]==1 && ghost.conversationStore.get("G")==null && ghost.deletedThreadIds.contains("G"),"not-loaded delete purges local residue and reports success");
 check(ghost.threads.length()==0 && !ghost.prefs.sets.get("p").contains("G") && ghost.prefs.sets.get("p").contains("K"),"purge drops thread listing and restore-set id only");
 check(App.isRemoteMissingThread(new RpcException(-32600,"thread not loaded: X")) && App.isRemoteMissingThread(new RpcException(-32600,"no rollout found for thread id Y")) && !App.isRemoteMissingThread(new RpcException(-32600,"already has an active writer")),"remote-missing classifier covers not-loaded and no-rollout only");
 App r=new App();r.profile=new Profile("p");r.connected=true;r.threadId="B";r.threads.put(listed("project-history","idle"));
 int[] refreshed={0};r.refreshWorkPanel(error->{refreshed[0]++;});
 check(!r.requestParams.has("cwd"),"work refresh is global read-only list, independent of project-browser filter");
 r.successes.get(0).accept(Json.obj("data",new JSONArray().put(listed("new-active","active"))));
 check(refreshed[0]==1 && r.threads.getJSONObject(0).optString("id").equals("project-history") && r.threadId.equals("B"),"successful work refresh finishes without changing project history or navigation");
 check(r.workConversations().stream().anyMatch(t->t.threadId.equals("new-active")),"new remote active task appears after refresh");
 r.refreshWorkPanel(error->{refreshed[0]++;});r.failures.get(1).accept(new Exception("fixture"));check(refreshed[0]==2,"refresh failure also invokes completion");
 r.refreshWorkPanel(error->{});r.refreshWorkPanel(error->{});
 r.successes.get(3).accept(Json.obj("data",new JSONArray().put(listed("fresh","active"))));
 r.successes.get(2).accept(Json.obj("data",new JSONArray().put(listed("stale","active"))));
 check(r.workThreads.getJSONObject(0).optString("id").equals("fresh"),"late old refresh cannot overwrite newer snapshot");
 }
}''')
        subprocess.run(['javac','-cp',str(JAR),'-d',str(directory),str(SRC/'Json.java'),str(test)],check=True)
        subprocess.run(['java','-cp',str(directory)+':'+str(JAR),'app.remotessh.WorkSyncTest'],check=True)
    panel=(SRC/'WorkPanel.java').read_text();activity=(SRC/'MainActivity.java').read_text()
    assert 'new PullRefreshScrollView' in panel and 'app.refreshWorkPanel(' in panel and 'scrollRef[0].finishRefresh()' in panel
    assert 'refreshGeneration[0]!=app.connectionGeneration()' in panel and 'refreshHint.removeCallbacks(timeout)' in panel
    assert '!method.equals("thread/delete")' in method(source,'  void request(String method,JSONObject params')
    assert 'icon("menu", "打开连接和聊天记录"' not in activity and 'app.connectStartupOnce();' in activity
    assert 'title.setText(getString(R.string.app_name));' in activity
    for name in ['WorkPanel.java','SftpBrowser.java','RemoteBrowser.java']:
        content=(SRC/name).read_text();assert 'setIncludeFontPadding(false)' in content and 'Gravity.CENTER_VERTICAL' in content
    browser=(SRC/'RemoteBrowser.java').read_text();assert 'CenteredMenu.show(activity,ui.fg,menuLabels()' in browser
    assert 'int[] selectedFilter = {0};' in panel and 'tabSwipe' in panel
    assert 'taskRow.setOnClickListener' not in panel and 'counts.setOnClickListener' in panel
    assert 'toggle.setGravity(Gravity.RIGHT' in panel
    assert 'showProcess(activity, ui, task)' in panel and 'setVerticalScrollBarEnabled(true)' in panel
    assert 'heightPixels * 0.60f' in panel and 'Gravity.BOTTOM' in panel
    assert 'Math.min(shown[0] + 30' in panel
    assert 'setOnLongClickListener' in panel and '删除该任务记录？' in panel
    print('PASS refresh cleanup, global delete routing, no hamburger, shared menu and centered header source wiring')
if __name__=='__main__':main()
