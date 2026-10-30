"""Production connection landing / session restore policy on plain JVM.
Transport, preferences and JSON are explicit test doubles; no real SSH/model calls.
"""
from pathlib import Path
import re
import subprocess
import tempfile
from test_history_navigation import method

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'

def main():
    source=(SRC/'RemoteApp.java').read_text()
    methods='\n'.join(method(source,s) for s in ('  void prepareConnectionLanding()', '  void restoreSession(JSONObject session, String resumeId)'))
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='connect-landing-test-', ignore_cleanup_errors=True) as folder:
        folder=Path(folder)
        java=folder/'ConnectionLandingTest.java'
        java.write_text('''package app.remotessh;
import java.util.*;
class JSONObject {
 String last; JSONArray threads; JSONObject(String last,String... ids){this.last=last;threads=new JSONArray(ids);}
 boolean optBoolean(String key){return true;}
 String optString(String key){return last;}
 JSONArray optJSONArray(String key){return threads;}
}
class JSONArray {String[] ids;JSONArray(String[] ids){this.ids=ids;}int length(){return ids.length;}String optString(int i){return ids[i];}}
class Profile {String id="profile";}
class Pref {Set<String> getStringSet(String key,Set<String> fallback){return fallback;}}
class RemoteApp {
 static final int MODE_PRIVATE=0;
 String reconnectThread="old",pendingNavigation="stale",explicitlyRestoredThread="",threadId="";
 boolean homeVisible,pendingHomeLanding,multiSessionHost;int selected,restores;
 Profile profile=new Profile();Set<String> recoveringThreads=new HashSet<>();ArrayDeque<String> restoreQueue=new ArrayDeque<>();
 Pref getSharedPreferences(String s,int mode){return new Pref();}
 void select(String id){selected++;threadId=id;homeVisible=false;}
 void restoreNext(){restores++;}
''' + methods + '''
}
public class ConnectionLandingTest {
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[] args){
 RemoteApp ordinary=new RemoteApp();ordinary.prepareConnectionLanding();
 check(ordinary.homeVisible && ordinary.reconnectThread.isEmpty(),"ordinary connect has no implicit resume target");
 check(ordinary.pendingNavigation.isEmpty() && !ordinary.pendingHomeLanding,"stale navigation reset on new connection");
 ordinary.restoreSession(new JSONObject("previous","previous","background"),ordinary.reconnectThread);
 check(ordinary.selected==0 && ordinary.threadId.isEmpty() && ordinary.homeVisible,"remote lastThread does not replace home suggestions");
 check(ordinary.restoreQueue.contains("background") && ordinary.restores==1,"background restoration still queued without foreground navigation");
 RemoteApp explicit=new RemoteApp();explicit.prepareConnectionLanding();explicit.reconnectThread="chosen";
 explicit.restoreSession(new JSONObject("previous","previous","background"),explicit.reconnectThread);
 check(explicit.selected==1 && explicit.threadId.equals("chosen"),"explicit reconnect-and-resume still selects chosen task");
 RemoteApp empty=new RemoteApp();empty.prepareConnectionLanding();empty.restoreSession(new JSONObject(""),"");
 check(empty.homeVisible && empty.selected==0,"empty remote session remains on home");
 }
}''')
        subprocess.run(['javac','-d',str(folder),str(java)],check=True)
        subprocess.run(['java','-cp',str(folder),'app.remotessh.ConnectionLandingTest'],check=True)
    connect=method(source,'  public void connect(Profile p)')
    assert 'prepareConnectionLanding();' in connect and 'reconnectThread = p.lastThread' not in connect
    reconnect=method(source,'  public void reconnectAndResume()')
    assert reconnect.index('connect(profile)') < reconnect.index('reconnectThread = id')
    activity=(SRC/'MainActivity.java').read_text()
    settings=method(activity,'  private void showSettings()')
    labels=re.search(r'String\[\] options = \{([^}]+)\}',settings).group(1)
    assert re.findall(r'"([^"]+)"',labels)==['外观设置','文字大小','后台设置','添加连接','当前连接','应用沙箱']
    assert 'which == 1) chooseFontSize();' in settings and 'which == 2) backgroundSettings();' in settings
    assert 'which == 5) SandboxSettings.show(this);' in settings
    background=method(activity,'  private void backgroundSettings()')
    assert '"Tailscale"' in background and 'TsnetSetup.show(this)' in background
    assert '连接期间保持唤醒与通知' not in background
    for label in ('介绍一下这个项目','检查项目中的问题','继续之前的工作'):
        assert '"'+label+'"' in method(activity,'  private void renderEmpty()')
    print('PASS exact settings order/action wiring and ordinary-versus-explicit connect wiring (source checks)')

if __name__=='__main__':main()
