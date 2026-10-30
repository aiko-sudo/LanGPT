"""Execute the production hold-to-copy path payload against Clipboard/Toast adapters on JVM.
This tests event wiring and payload, not Android's real clipboard/visual rendering.
"""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))

def main():
    browser=(SRC/'SftpBrowser.java').read_text()
    start=browser.index('  private void copyPath(')
    end=browser.index('\n  }\n',start)+len('\n  }\n')
    method=browser[start:end].replace('  private void copyPath(String full) {','  void copyPath(String full) {')
    for wiring in ['attachHoldActions(row,RemoteDirectory.child(path,entry.name),entry.name,entry.size)',
                   'timer.postDelayed(copy[0],1000)','timer.postDelayed(save[0],3000)',
                   'offerSave(full,name,size)','dx*dx+dy*dy>slop*slop']:
        assert wiring in browser, wiring
    assert 'row.setOnLongClickListener' not in browser
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='sftp-clipboard-test-', ignore_cleanup_errors=True) as folder:
        folder=Path(folder)
        stubs={
          'android/content/Context.java':'package android.content;public class Context {public static final String CLIPBOARD_SERVICE="clipboard";}',
          'android/content/ClipData.java':'package android.content;public class ClipData {public String text;public static ClipData newPlainText(String label,String text){ClipData c=new ClipData();c.text=text;return c;}}',
          'android/content/ClipboardManager.java':'package android.content;public class ClipboardManager {public ClipData clip;public void setPrimaryClip(ClipData clip){this.clip=clip;}}',
          'android/view/Gravity.java':'package android.view;public class Gravity {public static final int BOTTOM=80,CENTER_HORIZONTAL=1;}',
          'android/widget/Toast.java':'''package android.widget;public class Toast {
           public static final int LENGTH_SHORT=0;public static String text;public static boolean shown;public static int gravity;
           public static Toast makeText(Object activity,String message,int duration){text=message;shown=false;return new Toast();}
           public void setGravity(int value,int x,int y){gravity=value;}public void show(){shown=true;}}''',
        }
        for name,text in stubs.items():p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        test=folder/'ClipboardTest.java'
        test.write_text('''package app.remotessh;
class ClipboardTest {
 static class Activity {android.content.ClipboardManager clipboard=new android.content.ClipboardManager();Object getSystemService(String key){return clipboard;}}
 static class Ui {Activity activity=new Activity();int dp(int n){return n;}}
 static class Holder {Ui ui=new Ui();
''' + method + '''
 }
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);System.out.println("PASS "+text);}
 static void run(String path,RemoteFiles.Entry entry,String expected){Holder h=new Holder();
  h.copyPath(RemoteDirectory.child(path,entry.name));
  check(h.ui.activity.clipboard.clip.text.equals(expected),"clipboard exact full path "+expected);
  check(android.widget.Toast.shown && android.widget.Toast.text.equals("路径已复制") && (android.widget.Toast.gravity&80)==80,"bottom copied feedback requested");
 }
 public static void main(String[]args){
  run("/work/project",new RemoteFiles.Entry("a b.txt",false,false,1,1,""),"/work/project/a b.txt");
  run("/",new RemoteFiles.Entry(".hidden",false,false,1,1,""),"/.hidden");
  run("/work",new RemoteFiles.Entry("中文目录",true,false,0,1,""),"/work/中文目录");
 }
}''')
        java=[str(p) for p in folder.rglob('*.java')]+[str(SRC/n) for n in ['RemoteFiles.java','RemoteDirectory.java','Json.java']]
        subprocess.run(['javac','-cp',str(JAR),'-d',str(folder),*java],check=True)
        subprocess.run(['java','-cp',str(folder)+':'+str(JAR),'app.remotessh.ClipboardTest'],check=True)
    activity=(SRC/'MainActivity.java').read_text();panel=(SRC/'WorkPanel.java').read_text();menu=(SRC/'CenteredMenu.java').read_text()
    assert 'CenteredMenu.show(this,fg,muted,version,options' in activity and 'CenteredMenu.show(activity, ui.fg' in panel
    assert 'new String[]{"新建会话", "最近报错"}' in panel and '当前项目与对话' not in panel
    assert 'label.setGravity(Gravity.CENTER)' in menu and 'widthPixels*.60f' in menu and '.setTitle(' not in menu
    print('PASS shared title-free centered 60%-width menu and exactly two work actions (source checks)')
if __name__=='__main__':main()
