"""Compile and exercise production TaskProgress on plain JVM; no Android or model calls."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

def main():
    with tempfile.TemporaryDirectory(dir=ROOT / 'tools', prefix='task-progress-test-', ignore_cleanup_errors=True) as path:
        path = Path(path)
        test = path / 'TaskProgressTest.java'
        test.write_text('''package app.remotessh;
public class TaskProgressTest {
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[] args){
 TaskProgress p=new TaskProgress();
 check(p.elapsed(100000).equals("执行时间未知"),"unknown start is not fabricated");
 p.start("t1",0,100000);
 check(p.elapsed(162000).equals("已观察 1分2秒"),"observation duration labelled honestly");
 p.start("t1",0,160000);
 check(p.elapsed(162000).equals("已观察 1分2秒"),"duplicate start does not reset clock");
 p.start("t1",40000,162000);
 check(p.elapsed(162000).equals("已执行 2分2秒"),"server timestamp upgrades observed duration");
 p.finish(164000);
 check(p.elapsed(999000).equals("已执行 2分4秒"),"completion freezes clock");
 p.start("t2",0,200000);
 check(p.elapsed(205000).equals("已观察 5秒"),"new turn starts fresh timer");
 TaskProgress q=new TaskProgress();q.start("",0,500000);q.start("real-id",0,502000);
 check(q.elapsed(505000).equals("已观察 5秒"),"late turn id retains pending start");
 q.finish(506000);q.start("",0,600000);
 check(q.elapsed(602000).equals("已观察 2秒"),"next active status starts new observation");
 }
}''')
        subprocess.run(['javac', '-d', str(path), str(SRC / 'TaskProgress.java'), str(test)], check=True)
        subprocess.run(['java', '-cp', str(path), 'app.remotessh.TaskProgressTest'], check=True)
    app = (SRC / 'RemoteApp.java').read_text()
    assert 'id.equals(explicitlyRestoredThread)' in app
    view = app.split('public void viewConversation(String id)', 1)[1].split('\n  }', 1)[0]
    assert 'resume(' not in view and 'readThread(id, true)' in view
    activity = (SRC / 'MainActivity.java').read_text()
    assert 'if (returnToWorkPanel)' in activity and 'workPanelOriginHome' in activity
    assert 'app.viewConversation(id);' in activity.split('workPanel = WorkPanel.show', 1)[1]
    panel = (SRC / 'WorkPanel.java').read_text()
    assert 'app.workConversations()' in panel and 'open.accept(task.threadId)' in panel
    assert 'body.removeCallbacks(ticker)' in panel
    print('PASS navigation and read-only wiring structural checks (not device UI tests)')

if __name__ == '__main__':
    main()
