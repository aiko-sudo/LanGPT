"""Execute production Java lifecycle method bodies with task/IME doubles.

No Android runtime or SSH is started. Task ordering and IME rendering still need
phone acceptance; this tests the exact source methods rather than a Python copy.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class TerminalLifecycleTest(unittest.TestCase):
    def test_production_lifecycle_methods(self):
        source = (ROOT / 'app/src/main/java/app/remotessh/SshTerminalActivity.java').read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'public static void open(', 'private void confirmExit()', 'private void hideTerminalKeyboard()',
            '@Override protected void onStop()', '@Override protected void onResume()',
            '@Override public void onBackPressed()', '@Override protected void onDestroy()',
        ))
        files = {
            'android/view/inputmethod/InputMethodManager.java': '''package android.view.inputmethod;
public class InputMethodManager { public int hides;
public boolean hideSoftInputFromWindow(Object token,int flags){hides++;return true;} }''',
            'Harness.java': '''
class Intent {
  static final int FLAG_ACTIVITY_REORDER_TO_FRONT=0x20000;
  Class<?> target; int flags; boolean dark;
  Intent(Activity owner,Class<?> target){this.target=target;}
  Intent addFlags(int flags){this.flags|=flags;return this;}
  Intent putExtra(String key,boolean value){dark=value;return this;}
}
class Activity {
  static final String INPUT_METHOD_SERVICE="ime";
  Intent launched; int finishes,stops,resumes,destroys;
  android.view.inputmethod.InputMethodManager ime=new android.view.inputmethod.InputMethodManager();
  void startActivity(Intent i){launched=i;} void finish(){finishes++;}
  Object getSystemService(String name){return ime;}
  protected void onStop(){stops++;} protected void onResume(){resumes++;}
  protected void onDestroy(){destroys++;} public void onBackPressed(){finish();}
}
class AlertDialog {
  interface Click {void run(Object dialog,int which);}
  static AlertDialog latest; String message,negative,positive; Click confirm; boolean dismissed;
  void dismiss(){dismissed=true;}
  static class Builder {
    AlertDialog dialog=new AlertDialog(); Builder(Activity owner){}
    Builder setMessage(String text){dialog.message=text;return this;}
    Builder setNegativeButton(String text,Click action){dialog.negative=text;return this;}
    Builder setPositiveButton(String text,Click action){dialog.positive=text;dialog.confirm=action;return this;}
    AlertDialog show(){latest=dialog;return dialog;}
  }
}
class MainActivity extends Activity {}
class Terminal {
  View view=new View(); int backgrounds,refreshes,closes; boolean keyboard=true;
  void keyboard(boolean visible){keyboard=visible;}
  void background(){backgrounds++;} void resume(){refreshes++;} void close(){closes++;}
  static class View {boolean focused=true; Object getWindowToken(){return this;}
    void clearFocus(){focused=false;} }
}
class Writer {boolean stopped; void shutdownNow(){stopped=true;}}
class SshTerminalActivity extends Activity {
  Terminal terminal=new Terminal(); Writer writer=new Writer(); boolean destroyed;
  void clickArrow(){confirmExit();}
  AlertDialog ownedDialog; boolean connected=true; int disconnects;
  void disconnectAll(){if(!destroyed)throw new AssertionError("disconnect before destroy");disconnects++;connected=false;}
''' + methods + '''
}
public class Harness {
  static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
  public static void main(String[] args){
    Activity owner=new Activity(); SshTerminalActivity.open(owner,true);
    check(owner.launched.target==SshTerminalActivity.class && owner.launched.dark,"menu entry");
    check(owner.launched.flags==Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,"reuse terminal task instance");
    SshTerminalActivity page=new SshTerminalActivity(); Terminal retained=page.terminal;
    page.clickArrow();
    check(page.finishes==0 && page.disconnects==0 && page.connected,"arrow awaits confirmation");
    AlertDialog exit=AlertDialog.latest;
    check(exit.message.equals("断开 SSH 连接并退出？") && exit.negative.equals("取消") && exit.positive.equals("确认"),"exit menu");
    exit.dismiss();
    check(page.finishes==0 && page.connected,"cancel continues session");
    page.clickArrow(); AlertDialog.latest.confirm.run(null,1);
    check(page.finishes==1,"confirmed arrow finishes for onDestroy cleanup");
    page.finishes=0;
    page.onBackPressed();
    check(page.launched.target==MainActivity.class && page.launched.flags==Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,"return to main in same task");
    check(page.finishes==0 && page.disconnects==0 && page.connected,"back preserves connection and Activity");
    check(page.ime.hides==1 && !page.terminal.keyboard && !page.terminal.view.focused,"back hides IME/shortcuts and clears focus");
    page.onStop();page.onResume();page.onStop();page.onResume();
    check(page.terminal==retained && page.connected && page.disconnects==0,"background/resume retain session and page");
    check(retained.refreshes==2 && retained.backgrounds==3 && retained.closes==0,"resume only refreshes");
    page.onDestroy();
    check(page.destroyed && page.disconnects==1 && !page.connected && retained.closes==1 && page.writer.stopped,"destroy tears down transport and renderer");
    page=new SshTerminalActivity();page.connected=false;page.onBackPressed();
    check(page.finishes==0 && page.disconnects==0,"offline back also retains terminal history");
    page.clickArrow();
    check(page.finishes==1 && page.ownedDialog==null,"offline arrow exits without dialog");
    System.out.println("terminal Java lifecycle: back, reorder/reuse, IME, stop/resume and destroy PASS");
  }
}
''',
        }
        with tempfile.TemporaryDirectory(prefix='terminal-lifecycle-') as directory:
            work = Path(directory)
            for name, contents in files.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents)
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(work),
                            *map(str, work.rglob('*.java'))], check=True)
            subprocess.run(['java', '-cp', str(work), 'Harness'], check=True)


if __name__ == '__main__':
    unittest.main()
