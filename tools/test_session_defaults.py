"""Run production session-choice serialization and preference methods on plain JVM.
SharedPreferences adapter exercises save/reload without a real model or remote request.
"""
from pathlib import Path
import subprocess
import tempfile
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))

def main():
    source=(SRC/'ConversationPreferences.java').read_text()
    methods='\n'.join(method(source,s) for s in ['  void rememberLastSessionChoices()', '  void restoreLastSessionChoices()'])
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='session-defaults-test-', ignore_cleanup_errors=True) as folder:
        folder=Path(folder);context=folder/'android/content/Context.java';context.parent.mkdir(parents=True)
        context.write_text('package android.content;public class Context {public static final int MODE_PRIVATE=0;}')
        java=folder/'SessionChoicesTest.java'
        java.write_text('''package app.remotessh;
import java.util.*;import org.json.*;import android.content.Context;
class Profile {String id,runtime;Profile(String id,String runtime){this.id=id;this.runtime=runtime;}}
class Prefs {
 Map<String,String> disk;Prefs(Map<String,String> disk){this.disk=disk;}
 String getString(String key,String fallback){return disk.getOrDefault(key,fallback);}Editor edit(){return new Editor();}
 class Editor {Map<String,String> pending=new HashMap<>();Editor putString(String key,String value){pending.put(key,value);return this;}void apply(){disk.putAll(pending);}}
}
class App {Profile profile;String model="",reasoningEffort="";PermissionMode permissionMode=PermissionMode.INHERIT;Map<String,String> disk;
 App(Map<String,String> disk){this.disk=disk;}Prefs getSharedPreferences(String name,int mode){return new Prefs(disk);}}
class Choices {
 App app;Choices(App app){this.app=app;}
'''+methods+'''
}
public class SessionChoicesTest {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);System.out.println("PASS "+text);}
 public static void main(String[]args){
 Map<String,String> disk=new HashMap<>();App a=new App(disk);a.profile=new Profile("host-A","codex");
 a.model="gpt-6.1-sol";a.reasoningEffort="high";a.permissionMode=PermissionMode.DEFAULT;Choices choices=new Choices(a);choices.rememberLastSessionChoices();
 a.model="";a.reasoningEffort="";a.permissionMode=PermissionMode.INHERIT;choices.restoreLastSessionChoices();
 check(a.model.equals("gpt-6.1-sol") && a.reasoningEffort.equals("high") && a.permissionMode==PermissionMode.DEFAULT,"new draft restores previous explicit model/effort/permission");
 App restarted=new App(disk);restarted.profile=new Profile("host-A","codex");new Choices(restarted).restoreLastSessionChoices();
 check(restarted.model.equals(a.model) && restarted.permissionMode==PermissionMode.DEFAULT,"new app/store instance restores saved options");
 a.profile=new Profile("host-B","codex");a.model="b-model";a.permissionMode=PermissionMode.READ_ONLY;choices.rememberLastSessionChoices();
 a.profile=new Profile("host-A","codex");choices.restoreLastSessionChoices();check(a.model.equals("gpt-6.1-sol") && a.permissionMode==PermissionMode.DEFAULT,"separate connection choices never overwrite each other");
 App other=new App(disk);other.profile=new Profile("host-A","claude");new Choices(other).restoreLastSessionChoices();
 check(other.model.isEmpty() && other.permissionMode==PermissionMode.INHERIT,"different runtime does not inherit unrelated model or elevated permission");
 a.model="";a.reasoningEffort="";a.permissionMode=PermissionMode.INHERIT;choices.rememberLastSessionChoices();a.model="old";a.permissionMode=PermissionMode.FULL;choices.restoreLastSessionChoices();
 check(a.model.isEmpty() && a.permissionMode==PermissionMode.INHERIT,"explicit return-to-default choices are remembered, not replaced by older explicit choice");
 a.permissionMode=PermissionMode.FULL;choices.rememberLastSessionChoices();a.permissionMode=PermissionMode.INHERIT;choices.restoreLastSessionChoices();
 check(a.permissionMode==PermissionMode.FULL,"explicit user-selected permission mode is preserved exactly");
 JSONObject encoded=new JSONObject(disk.get(SessionDefaults.key("host-A","codex")));
 check(encoded.keySet().equals(new HashSet<>(Arrays.asList("model","effort","permission"))),"no approval responses or force-grant state stored");
 SessionDefaults unknown=SessionDefaults.decode("{\\"model\\":\\"x\\",\\"permission\\":\\"BOGUS\\"}");
 check(unknown.permission==PermissionMode.INHERIT,"unknown permission safely falls back to inherit");
 check(SessionDefaults.decode("broken-json")==null,"corrupt saved selection cannot fabricate defaults");
 App none=new App(disk);new Choices(none).rememberLastSessionChoices();check(none.model.isEmpty(),"no connection causes no save or cross-host fallback");
 }
}''')
        files=[str(SRC/n) for n in ['Json.java','PermissionMode.java','SessionDefaults.java']]
        subprocess.run(['javac','-cp',str(JAR),'-d',str(folder),str(context),*files,str(java)],check=True)
        subprocess.run(['java','-cp',str(folder)+':'+str(JAR),'app.remotessh.SessionChoicesTest'],check=True)
    app=(SRC/'RemoteApp.java').read_text();picker=(SRC/'ModelPicker.java').read_text();turn=(SRC/'TurnController.java').read_text()
    for signature in ['  void landHome()', '  public void newChat()', '  public void connect(Profile p)']:
        assert 'restoreLastSessionChoices();' in method(app,signature)
    assert 'rememberLastSessionChoices();' in method(app,'  void choosePermission(')
    assert 'app.rememberLastSessionChoices();' in picker and 'app.rememberLastSessionChoices();' in turn
    assert 'supports("approval") ? app.permissionMode : PermissionMode.INHERIT' in turn
    assert 'supports("models") && !app.model.isEmpty()' in turn
    print('PASS draft/new-chat/connect inheritance and existing runtime capability guards wired in production source')
if __name__=='__main__':main()
