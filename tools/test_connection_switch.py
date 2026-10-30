"""Exercise extracted production switch methods on JVM; no SSH or model calls."""
from pathlib import Path
import subprocess,tempfile
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'

def main():
    source=(SRC/'MainActivity.java').read_text()
    methods='\n'.join(method(source,key).replace('private void','void') for key in
        ['  private void connectProfile(', '  private void switchConnection('])
    java='''import java.util.*;
interface Listener {void click(Object d,int w);}
class AlertDialog {
 static Listener positive;static int prompts;
 static class Builder {
  Builder(Object activity){prompts++;}Builder setTitle(String s){return this;}Builder setMessage(String s){return this;}
  Builder setNegativeButton(String s,Listener l){return this;}
  Builder setPositiveButton(String s,Listener l){positive=l;return this;}void show(){}
 }
}
class Profile {String id,password="saved",privateKey="";boolean tailscaleSsh;Profile(String id){this.id=id;}}
class ConversationState {boolean remoteRunning,busy,readOnly;List<String> approvals=new ArrayList<>();}
class App {
 Profile profile;boolean connected,connecting,attaching,attachingFiles,busy,readOnly,remoteRunning;
 String draftText="";List<String> approvals=new ArrayList<>(),draftImages=new ArrayList<>(),draftFiles=new ArrayList<>();
 List<ConversationState> tasks=new ArrayList<>();boolean pending;void changed(){}List<ConversationState> workConversations(){return tasks;}
}
class TurnController {App a;static TurnController of(App a){TurnController t=new TurnController();t.a=a;return t;}boolean hasPendingForCurrentProfile(){return a.pending;}}
class SftpContent {static boolean active;static boolean hasActiveOperations(){return active;}}
class Input {String text="";String getText(){return text;}void setText(String s){text=s;}}
class Dialog {void dismiss(){}}
class Activity {
 App app=new App();Profile previousConnection;boolean switchingConnection,returnToWorkPanel;
 int activeMediaDownloads,connects,edits;String toast="";Input input=new Input();
 Map<String,String> connectionDrafts=new HashMap<>();Dialog drawer,workPanel,pendingQueueDialog;
 void toast(String s){toast=s;}void editProfile(Profile p,boolean fresh){edits++;}
 void beginConnect(Profile p){connects++;app.profile=p;app.connected=false;app.connecting=true;app.draftText="";app.draftImages.clear();app.draftFiles.clear();}
''' + methods + '''
}
public class SwitchTest {
 static int checks;static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 static Activity idle(){Activity a=new Activity();a.app.profile=new Profile("A");a.app.connected=true;AlertDialog.positive=null;return a;}
 public static void main(String[] args){
 Profile b=new Profile("B");Activity a=idle();a.input.text="draft A";a.connectProfile(b);
 check(a.connects==1 && a.previousConnection.id.equals("A"),"idle switch executes once and remembers previous");
 check(a.input.text.isEmpty() && a.connectionDrafts.get("A").equals("draft A"),"draft stays with original profile");
 a.app.connecting=false;a.app.connected=true;a.connectProfile(a.previousConnection);
 check(a.input.text.equals("draft A"),"switch back restores original text draft");
 a=idle();a.connectProfile(a.app.profile);check(a.connects==0,"current connected row is a no-op");
 a=idle();a.app.attaching=true;a.connectProfile(b);check(a.connects==0 && AlertDialog.positive==null,"upload blocks switching");
 a=idle();a.activeMediaDownloads=1;a.connectProfile(b);check(a.connects==0,"media download blocks switching");
 a=idle();SftpContent.active=true;a.connectProfile(b);check(a.connects==0,"SFTP file save/transfer blocks switching");SftpContent.active=false;
 a=idle();a.app.connecting=true;a.connectProfile(b);check(a.connects==0,"in-flight connection is not interrupted");
 a=idle();ConversationState t=new ConversationState();t.remoteRunning=true;a.app.tasks.add(t);a.connectProfile(b);
 check(a.connects==0 && AlertDialog.positive!=null,"background running task requires confirmation");
 AlertDialog.positive.click(null,0);check(a.connects==1,"confirmation performs switch");
 a=idle();a.app.approvals.add("approval");a.connectProfile(b);check(a.connects==0 && AlertDialog.positive!=null,"approval requires confirmation; cancel changes nothing");
 a=idle();a.app.pending=true;a.connectProfile(b);check(a.connects==0 && AlertDialog.positive!=null,"pending queue requires confirmation");
 a=idle();a.app.pending=true;a.connectProfile(b);a.app.attaching=true;AlertDialog.positive.click(null,0);check(a.connects==0,"guard is rechecked after confirmation");
 a=idle();a.app.busy=true;a.app.readOnly=true;a.connectProfile(b);check(a.connects==1,"history loading is not mistaken for active execution");
 a=idle();a.app.draftFiles.add("attachment");a.connectProfile(b);check(a.connects==0 && AlertDialog.positive!=null,"unsent attachments require explicit warning");
 a=idle();Profile missing=new Profile("C");missing.password="";a.connectProfile(missing);check(a.edits==1 && a.connects==0,"missing credentials opens editor without disconnecting");
 System.out.println("TOTAL "+checks);
 }
}
'''
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='switch-test-', ignore_cleanup_errors=True) as d:
        f=Path(d)/'SwitchTest.java';f.write_text(java)
        subprocess.run(['javac',str(f)],check=True)
        subprocess.run(['java','-cp',d,'SwitchTest'],check=True)
    remote=(SRC/'RemoteApp.java').read_text()
    connect=method(remote,'  public void connect(Profile') if '  public void connect(Profile' in remote else method(remote,'  void connect(Profile')
    marker='putString("lastProfileId",profile.id)'
    initialization=method(remote,'  void completeInitialization(')
    assert 'putString("lastProfileId"' not in connect
    assert initialization.count(marker)==1 and initialization.index(marker)>initialization.index('connected = true;')
    assert 'ConnectionPicker.show' in method(source,'  private void editCurrentProfile()')
    picker=(SRC/'ConnectionPicker.java').read_text()
    assert all(s in picker for s in ['编辑当前连接','重试当前连接','返回原连接','RelativeSizeSpan','case "claude": return "CC"'])
    switch=method(source,'  private void switchConnection(')
    assert 'reconnectAndResume' not in switch and 'app.resume(' not in switch
    assert switch.index('beginConnect(p);')<switch.index('switchingConnection=true;')
    print('PASS success-only default, picker actions, switch failure tracking and no auto-resume wiring')
if __name__=='__main__':main()
