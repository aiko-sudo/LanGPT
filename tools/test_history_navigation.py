"""JVM regression of production viewConversation/navigation gates and ConversationStore.
Uses explicit Android/data/request test doubles, not a device UI or real RPC test.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]

def main():
    source = (SRC / 'RemoteApp.java').read_text()
    methods = '\n'.join(method(source, sig) for sig in (
        '  boolean canSwitchConversation()', '  boolean recoveryInProgress()',
        '  void landHome()', '  public void viewConversation(String id)'))
    with tempfile.TemporaryDirectory(dir=ROOT / 'tools', prefix='history-navigation-test-', ignore_cleanup_errors=True) as path:
        path = Path(path)
        files = {
            'android/os/Looper.java': 'package android.os; public class Looper { static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;} public Looper getLooper(){return MAIN;} }',
            'org/json/JSONObject.java': 'package org.json; public class JSONObject {}',
            'app/remotessh/ConversationStore.java': (SRC / 'ConversationStore.java').read_text(),
            'app/remotessh/RemoteApp.java': '''package app.remotessh;
import java.util.*;import android.os.Looper;
class RemoteApp {
 final Looper main=Looper.myLooper();final ConversationStore conversationStore=new ConversationStore(this);
 final Set<String> loadedThreads=new HashSet<>();final Map<String,String> conversationLoads=new HashMap<>();
 String threadId="",model="",reasoningEffort="",permissionMode="",pendingNavigation="",loadingThread="",restoringThread="",replayThread="";
 boolean connected=true,restartingRemote,attaching,busy,replaying,homeVisible=true,pendingHomeLanding;
 int reads,progress;Profile profile=new Profile();
 void checkpoint(){conversationStore.checkpoint();}void changed(){checkpoint();}
 void select(String id){conversationStore.select(id);homeVisible=false;}
 void readThread(String id,boolean readonly){if(!readonly)throw new AssertionError("not read only");reads++;}
 void restoreLastSessionChoices(){}
 void restoreServiceTier(ConversationState s,String id){}void restorePermission(ConversationState s,String id){}void restoreInteractions(ConversationState s,String id){}
 void deliver(String id){conversationStore.dispatch(id,()->{progress=99;busy=false;conversationLoads.remove(id);changed();});}
''' + methods + '\n}',
            'app/remotessh/ConversationState.java': '''package app.remotessh;
import java.util.*;import org.json.JSONObject;
class ConversationState {
 String threadId="",model,effort,permission;boolean busy,replaying,readOnly;int progress;
 final List<JSONObject> approvals=new ArrayList<>();
 void capture(RemoteApp a){threadId=a.threadId;busy=a.busy;replaying=a.replaying;progress=a.progress;}
 void apply(RemoteApp a){a.threadId=threadId;a.busy=busy;a.replaying=replaying;a.progress=progress;}
}
class Profile {String cwd="",lastThread="";}
''',
            'app/remotessh/HistoryNavigationTest.java': '''package app.remotessh;
public class HistoryNavigationTest {
 static void check(boolean x,String name){if(!x)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[] args){
 RemoteApp a=new RemoteApp();a.viewConversation("A");
 check(a.reads==1 && a.recoveryInProgress(),"A history starts read-only and shows its own loading");
 a.landHome();check(a.threadId.isEmpty() && !a.recoveryInProgress() && a.canSwitchConversation(),"return home while A is loading never blocks");
 a.viewConversation("B");check(a.threadId.equals("B") && a.reads==2,"switch to B without waiting for A");
 a.deliver("A");check(a.threadId.equals("B") && a.busy && a.recoveryInProgress(),"late A result cannot switch page or clear B loading");
 a.viewConversation("B");check(a.reads==2,"reopening a loading task does not duplicate read");
 a.landHome();a.deliver("B");check(a.threadId.isEmpty() && a.homeVisible,"late B result cannot steal home navigation");
 a.viewConversation("A");check(a.progress==99 && a.reads==3,"background result retained in original cache");
 a.landHome();a.restoringThread="C";a.loadingThread="C";
 check(a.canSwitchConversation() && !a.recoveryInProgress(),"unrelated restore cannot lock home");
 a.pendingHomeLanding=true;a.viewConversation("C");
 check(a.threadId.equals("C") && !a.pendingHomeLanding && a.reads==3,"explicit navigation wins and reuses pending restore");
 }
}'''
        }
        for filename, text in files.items():
            out=path/filename;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(text)
        subprocess.run(['javac','-d',str(path/'classes')]+[str(path/f) for f in files],check=True)
        subprocess.run(['java','-cp',str(path/'classes'),'app.remotessh.HistoryNavigationTest'],check=True)
    activity=(SRC/'MainActivity.java').read_text()
    back=method(activity,'  private void navigateBack()')
    assert 'recoveryInProgress' not in back and 'app.attaching' not in back
    settings=method(activity,'  private void showSettings()')
    menu=method(activity,'  private void showMainMenu()')
    assert all('"'+label+'"' in settings for label in ('外观设置','文字大小','后台设置','添加连接','当前连接'))
    assert '"电池设为不受限"' in activity and '"Tailscale"' in activity
    assert '流体云与通知' not in activity and '收起状态只隐藏本轮流体云' not in activity
    assert '显示任务状态与审批流体云' in activity and '打开系统通知设置' in activity
    assert '连接期间会显示常驻通知并保持' not in activity
    assert '添加连接' not in menu and '修改当前连接' not in menu
    print('PASS back is ungated; five settings entries and no duplicate top-level entries (source checks)')

    source=(SRC/'MainActivity.java').read_text()
    assert 'boolean showActions = app.readOnly && !resuming;' in source
    assert 'roActions.animate().alpha(1f)' in source and 'withEndAction' in source
    remote=Path(SRC).joinpath('RemoteApp.java').read_text()
    assert 'isThreadNotLoaded' in remote and '该会话未在电脑端加载' in remote
    assert 'isRemoteMissingThread' in remote and 'getStringSet(profile.id' in remote
    assert 'startAnimation' not in source
    assert 'action(\"接续\"' in source and 'action(\"新会话\"' in source
    assert '重连并接续' not in source
    print('PASS history page keeps resume/new-chat visible while read-only (source checks)')
if __name__=='__main__':main()
