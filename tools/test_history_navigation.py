"""JVM regression of production viewConversation/navigation gates and ConversationStore.
Uses explicit Android/data/request test doubles, not a device UI or real RPC test.
"""
from pathlib import Path
import subprocess
import tempfile
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

def resources():
    result = {}
    for path in (ROOT / 'app/src/main/res/values').glob('*.xml'):
        for item in ET.parse(path).getroot().findall('string'):
            text = ''.join(item.itertext()).strip()
            if text.startswith('"') and text.endswith('"'):
                text = text[1:-1]
            result[item.attrib['name']] = text.replace('\\u0020', ' ')
    return result


def assert_resource(source, name, expected):
    assert 'R.string.' + name in source, name
    assert expected in resources()[name], (name, expected)


def java_text_stubs(source):
    names = sorted(set(re.findall(r'R\.string\.(\w+)', source)))
    values = resources()
    fields = ' '.join('static final int %s=%d;' % (name, i) for i, name in enumerate(names))
    cases = ' '.join('case %d: return %s;' % (i, json.dumps(values[name], ensure_ascii=True)) for i, name in enumerate(names))
    return 'class R { static class string { ' + fields + ' }}\n' + 'class UiText { static String get(int id) { switch(id) { ' + cases + ' default: throw new AssertionError(id); } }}\n'


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
        '  void landHome()', '  public void viewConversation(String id)',
        '  private void openCrossChannelConversation(ConversationState s)', '  private void resumeCrossChannel(ConversationState source)'))
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
 final Preferences preferences=new Preferences(); static class Preferences { String lastSessionModel(){return "last-model";} }
 final Set<String> loadedThreads=new HashSet<>();final Map<String,String> conversationLoads=new HashMap<>();
 String threadId="",model="",reasoningEffort="",permissionMode="",pendingNavigation="",loadingThread="",restoringThread="",replayThread="";
 boolean connected=true,restartingRemote,attaching,busy,replaying,readOnly,homeVisible=true,pendingHomeLanding;
 long ctxUsedTokens=-1,ctxTotalTokens=-1;
 int reads,progress;Profile profile=new Profile();
 void checkpoint(){conversationStore.checkpoint();}void changed(){checkpoint();}
 void select(String id){conversationStore.select(id);homeVisible=false;}
 void leaveIdleConversation(){} boolean currentThreadRunning(){return busy;} void releaseThread(String id){loadedThreads.remove(id);}
 void readThread(String id,boolean readonly){if(!readonly)throw new AssertionError("not read only");reads++;}
 String conversationChannel(String id,String runtime){return runtime.isEmpty()?"codex":runtime;}
 void readOtherThread(String id,String channel){}
 String error="",state="",threadTitle="",threadCwd="",pendingContextPrefix="";
 final List<ChatItem> items=new ArrayList<>();
 static String buildContextPrefix(List<ChatItem> items){return items.isEmpty()?"":items.get(0).text;}
 void testCross(ConversationState s){openCrossChannelConversation(s);}
 void testResume(ConversationState s){resumeCrossChannel(s);}

 void restoreLastSessionChoices(){}
 void restoreServiceTier(ConversationState s,String id){}void restorePermission(ConversationState s,String id){}void restoreInteractions(ConversationState s,String id){}
 void deliver(String id){conversationStore.dispatch(id,()->{progress=99;busy=false;conversationLoads.remove(id);changed();});}
''' + methods + '\n}',
            'app/remotessh/ConversationState.java': '''package app.remotessh;
import java.util.*;import org.json.JSONObject;
class ConversationState {
 String threadId="",model,effort,permission,runtime="",title="",cwd=""; final List<ChatItem> items=new ArrayList<>(); boolean busy,replaying,readOnly;int progress;
 final List<JSONObject> approvals=new ArrayList<>();
 void capture(RemoteApp a){threadId=a.threadId;busy=a.busy;replaying=a.replaying;readOnly=a.readOnly;progress=a.progress;title=a.threadTitle;cwd=a.threadCwd;items.clear();items.addAll(a.items);}
 void apply(RemoteApp a){a.threadId=threadId;a.busy=busy;a.replaying=replaying;a.readOnly=readOnly;a.progress=progress;a.threadTitle=title;a.threadCwd=cwd;a.items.clear();a.items.addAll(items);}
}
class Profile {String cwd="",lastThread="",runtime="codex";}
class ChatItem {String text;ChatItem(String t){text=t;}}
''',
            'app/remotessh/HistoryNavigationTest.java': '''package app.remotessh;
public class HistoryNavigationTest {
 static void check(boolean x,String name){if(!x)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[] args){
 RemoteApp a=new RemoteApp();a.viewConversation("A");
 check(a.reads==1 && a.recoveryInProgress(),"A history starts read-only and shows its own loading");
 check(a.model.equals("last-model"),"history inherits saved model when unset");
 a.landHome();check(a.threadId.isEmpty() && !a.recoveryInProgress() && a.canSwitchConversation(),"return home while A is loading never blocks");
 a.viewConversation("B");check(a.threadId.equals("B") && a.reads==2,"switch to B without waiting for A");
 a.deliver("A");check(a.threadId.equals("B") && a.busy && a.recoveryInProgress(),"late A result cannot switch page or clear B loading");
 a.viewConversation("B");check(a.reads==2,"reopening a loading task does not duplicate read");
 a.landHome();a.deliver("B");check(a.threadId.isEmpty() && a.homeVisible,"late B result cannot steal home navigation");
 a.viewConversation("A");check(a.progress==99 && a.reads==3,"background result retained in original cache");
 a.landHome();a.restoringThread="C";a.loadingThread="C";
 check(a.canSwitchConversation() && !a.recoveryInProgress(),"unrelated restore cannot lock home");
 a.landHome(); a.conversationStore.dispatch("foreign",()->{a.items.add(new ChatItem("old context"));});
 ConversationState foreign=a.conversationStore.get("foreign"); a.testCross(foreign);
 check(a.readOnly && !a.homeVisible && a.threadId.equals("foreign"),"cross-channel history remains read-only on its own page");
 a.testResume(foreign);check(!a.readOnly && !a.homeVisible && a.threadId.isEmpty() && a.pendingContextPrefix.equals("old context"),"explicit resume copies context to new channel draft");
 a.landHome();
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
    background=method(activity,'  private void backgroundSettings()')
    for name, label in [('main_text_19','后台设置'),('main_text_16','添加连接'),('main_text_17','当前连接')]:
        assert_resource(settings,name,label)
    for name, label in [('main_text_21','外观设置'),('main_text_20','文字大小'),('ui_battery_unrestricted','电池设为不受限')]:
        assert_resource(background,name,label)
    assert 'which == 0) backgroundSettings()' in settings
    assert 'which == 1) editProfile(new Profile(), true)' in settings
    assert 'which == 2) editCurrentProfile()' in settings
    assert 'which == 4) chooseTheme()' in background and 'which == 5) chooseFontSize()' in background
    assert '"Tailscale"' in background
    assert '流体云与通知' not in activity and '收起状态只隐藏本轮流体云' not in activity
    assert_resource(activity,'main_text_182','显示任务状态与审批流体云')
    assert_resource(activity,'main_text_183','打开系统通知设置')
    assert '连接期间会显示常驻通知并保持' not in activity
    assert 'showSettings();' in menu
    print('PASS back is ungated; settings and migrated background menu actions are wired')

    source=(SRC/'MainActivity.java').read_text()
    assert 'boolean showActions = app.readOnly && !resuming;' in source
    assert 'roActions.animate().alpha(1f)' in source and 'withEndAction' in source
    remote=Path(SRC).joinpath('RemoteApp.java').read_text()
    assert 'isThreadNotLoaded' in remote and '该会话未在电脑端加载' in remote
    assert 'isRemoteMissingThread' in remote and 'getStringSet(profile.id' in remote
    assert 'startAnimation' not in source
    assert 'action(UiText.get(R.string.main_text_12), () -> app.resume(app.threadId))' in source
    assert 'else if (which == 4) startNewChat();' in settings
    assert 'roActions.addView(resumeButton, new LinearLayout.LayoutParams(-1, -2))' in source
    assert 'newChatButton' not in source
    assert_resource(source,'main_text_12','接续')
    assert_resource(source,'main_text_13','新会话')
    assert '重连并接续' not in source
    print('PASS history page offers full-width resume and new chat in the main menu (source checks)')
if __name__=='__main__':main()
