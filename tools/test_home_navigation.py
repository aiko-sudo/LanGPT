"""JVM regression harness for actual ConversationStore and RemoteApp.landHome.
Android/JSON and per-thread data are test doubles; no device UI claims.
"""
import pathlib
import re
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

def main():
    app = (SRC / 'RemoteApp.java').read_text()
    home = re.search(r'  void landHome\(\) \{.*?\n  \}', app, re.S).group()
    assert 'thread/unsubscribe' not in home and 'clearServerRequests' not in home
    activity = (SRC / 'MainActivity.java').read_text()
    assert 'this::navigateBack' in activity and 'public void onBackPressed()' in activity
    assert 'app.landHome();' in activity
    with tempfile.TemporaryDirectory(dir=ROOT / 'tools', prefix='navigation-test-', ignore_cleanup_errors=True) as d:
        d = pathlib.Path(d)
        files = {
            'android/os/Looper.java': 'package android.os; public class Looper { static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;} public Looper getLooper(){return MAIN;} }',
            'org/json/JSONObject.java': 'package org.json; public class JSONObject {}',
            'app/remotessh/ConversationStore.java': (SRC / 'ConversationStore.java').read_text(),
            'app/remotessh/RemoteApp.java': '''package app.remotessh;
import android.os.Looper;
class RemoteApp {
 final Looper main=Looper.myLooper(); final ConversationStore conversationStore=new ConversationStore(this);
 String threadId="", model="", reasoningEffort="", permissionMode="", pendingNavigation="";
 boolean pendingHomeLanding,homeVisible,busy; int progress, approvals, changes;
 Profile profile=new Profile();
 void changed(){if(!conversationStore.deferChanges())changes++;}
 void restoreLastSessionChoices(){}
 void restoreServiceTier(ConversationState s,String id){}
 void restorePermission(ConversationState s,String id){}
 void restoreInteractions(ConversationState s,String id){}
''' + home + '\n}',
            'app/remotessh/ConversationState.java': '''package app.remotessh;
import java.util.*; import org.json.JSONObject;
class ConversationState {
 String threadId="",model,effort,permission; boolean busy,replaying,readOnly;
 int progress,approvalCount; final List<JSONObject> approvals=new ArrayList<>();
 void capture(RemoteApp a){threadId=a.threadId;busy=a.busy;progress=a.progress;approvalCount=a.approvals;}
 void apply(RemoteApp a){a.threadId=threadId;a.busy=busy;a.progress=progress;a.approvals=approvalCount;}
}
class Profile {String cwd="",lastThread="";}
''',
            'app/remotessh/HomeNavigationTest.java': '''package app.remotessh;
public class HomeNavigationTest {
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[] args){
 RemoteApp a=new RemoteApp();a.conversationStore.select("taskA");a.busy=true;a.progress=12;a.approvals=1;
 a.landHome();
 check(a.homeVisible && a.threadId.isEmpty() && !a.busy,"running task returns to home draft");
 ConversationState old=a.conversationStore.get("taskA");
 check(old.busy && old.progress==12 && old.approvalCount==1,"running state and approvals retained");
 a.conversationStore.dispatch("taskA",()->{a.progress=13;});
 check(a.threadId.isEmpty() && a.conversationStore.get("taskA").progress==13,"background event updates original task without navigating");
 a.conversationStore.select("taskB");a.busy=true;a.progress=1;a.landHome();
 check(a.conversationStore.get("taskA").busy && a.conversationStore.get("taskB").busy,"two tasks retained independently");
 a.conversationStore.select("taskA");
 check(a.progress==13 && a.approvals==1,"reopening original task restores latest state");
 RemoteApp fresh=new RemoteApp();fresh.busy=true;fresh.conversationStore.checkpoint();
 fresh.threadId="allocated-task";fresh.conversationStore.promoteDraft();fresh.landHome();
 check(!fresh.busy && fresh.threadId.isEmpty(),"consumed home draft cannot retain stale busy state");
 fresh.conversationStore.select("parallel-task");fresh.busy=true;fresh.progress=5;
 fresh.conversationStore.dispatch("allocated-task",()->{fresh.busy=false;fresh.progress=99;});
 check(fresh.threadId.equals("parallel-task") && fresh.busy && fresh.progress==5,
     "background completion cannot clear foreground task state");
 }
}'''
        }
        for name, text in files.items():
            p = d / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        subprocess.run(['javac', '-d', str(d / 'classes')] + [str(d / n) for n in files], check=True)
        subprocess.run(['java', '-cp', str(d / 'classes'), 'app.remotessh.HomeNavigationTest'], check=True)

if __name__ == '__main__':
    main()
