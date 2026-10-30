"""Exercise production pull-refresh direction/threshold logic without Android native runtime."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

def main():
    with tempfile.TemporaryDirectory(dir=ROOT / 'tools', prefix='pull-refresh-test-', ignore_cleanup_errors=True) as folder:
        folder = Path(folder)
        test = folder / 'PullRefreshTest.java'
        test.write_text('''package app.remotessh;
public class PullRefreshTest {
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[] args){
 PullRefreshGesture g=new PullRefreshGesture(8,72);
 g.begin(0,0,true,false);g.move(0,80,true);
 check(g.release(),"top downward pull triggers refresh");
 check(!g.release(),"one release triggers at most once");
 g.begin(0,0,true,false);g.move(0,40,true);
 check(!g.release(),"short pull cancels");
 g.begin(100,0,true,false);g.move(50,1,true);g.move(45,100,true);
 check(!g.release(),"horizontal swipe locks out refresh even after downward drift");
 g.begin(0,100,true,false);g.move(0,50,true);g.move(0,200,true);
 check(!g.release(),"upward scrolling locks out refresh");
 g.begin(0,0,false,false);g.move(0,120,true);
 check(!g.release(),"mid-list gesture cannot refresh after reaching top");
 g.begin(0,0,true,true);g.move(0,120,true);
 check(!g.release(),"refresh in progress blocks duplicate pulls");
 g.begin(0,0,true,false);g.move(0,120,true);g.cancel();
 check(!g.release(),"cancel event never refreshes");
 g.begin(0,0,true,false);g.move(0,100,true);g.move(0,10,true);
 check(!g.release(),"retracting below threshold cancels refresh");
 }
}''')
        subprocess.run(['javac','-d',str(folder),str(SRC/'PullRefreshGesture.java'),str(test)],check=True)
        subprocess.run(['java','-cp',str(folder),'app.remotessh.PullRefreshTest'],check=True)
    browser = (SRC/'RemoteBrowser.java').read_text()
    activity = (SRC/'MainActivity.java').read_text()
    assert 'new String[] {"SFTP", "SSH 终端", "独立工作区"}' in browser and 'actions.sftp(' in browser
    assert '刷新项目与对话' not in browser
    assert 'this::refreshByPull' in browser and 'app.refreshThreads(false,' in browser
    main_menu = activity.split('private void showMainMenu()',1)[1].split('private void chatMenu()',1)[0]
    assert 'showSettings();' in main_menu and 'String[] options' not in main_menu
    print('PASS menu relocation and pull-refresh callback wiring (source checks, not device UI)')

if __name__ == '__main__':
    main()
