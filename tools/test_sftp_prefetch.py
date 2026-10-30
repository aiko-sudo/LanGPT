"""Exercise production one-shot prefetch policy with transport doubles and sorting on JVM."""
from pathlib import Path
import subprocess
import tempfile
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'

def main():
    app=(SRC/'RemoteApp.java').read_text()
    methods='\n'.join(method(app,s) for s in ('  private static String sftpCacheKey(',
        '  RemoteFiles cachedSftp(', '  void cacheSftp(', '  void prefetchSftpOnce()'))
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='sftp-prefetch-test-', ignore_cleanup_errors=True) as folder:
        folder=Path(folder);java=folder/'PrefetchTest.java'
        java.write_text('''package app.remotessh;
import java.util.*;import java.util.concurrent.*;
class Profile {
 String id="p",host="host",user="user",cwd="/work";int port=22;
 Profile json(){return this;}static Profile from(Profile p){Profile c=new Profile();c.id=p.id;c.host=p.host;c.user=p.user;c.cwd=p.cwd;c.port=p.port;return c;}
}
class Vault {List<Profile> profiles=new ArrayList<>();}
class Main {void post(Runnable r){r.run();}}
class SshTransport {
 interface Trust {boolean confirm(String h,String f)throws Exception;}
 static int attempts,reads,closed;static boolean fail;
 void connectFiles(Profile p,Vault v,Trust t,int timeout)throws Exception{
 attempts++;if(timeout!=5000 || t.confirm("unknown","untrusted"))throw new AssertionError("unsafe prefetch");
 if(fail)throw new java.io.IOException("fixture unreachable");
 }
 RemoteFiles listFiles(String path){reads++;return new RemoteFiles(path,new ArrayList<>());}
 void close(){closed++;}
}
class RemoteApp {
 boolean sftpPrefetchAttempted;final LinkedHashMap<String,RemoteFiles> sftpSnapshots=new LinkedHashMap<>();
 Profile profile;Vault vault=new Vault();Main main=new Main();Executor worker=Runnable::run;int changes;
 void changed(){changes++;}
 Profile startupProfile(){return profile!=null?profile:vault.profiles.isEmpty()?null:vault.profiles.get(0);}
''' + methods + '''
}
public class PrefetchTest {
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[]args){
 RemoteApp empty=new RemoteApp();empty.prefetchSftpOnce();check(SshTransport.attempts==0,"no saved connection means no network attempt");
 RemoteApp a=new RemoteApp();a.vault.profiles.add(new Profile());a.prefetchSftpOnce();a.prefetchSftpOnce();
 check(SshTransport.attempts==1 && SshTransport.reads==1,"startup prefetch authenticates and reads only once");
 check(a.cachedSftp(a.vault.profiles.get(0),"/work")!=null,"successful prefetch caches current work directory");
 check(SshTransport.closed==1,"successful prefetch closes independent SSH transport");
 SshTransport.attempts=0;SshTransport.reads=0;SshTransport.closed=0;SshTransport.fail=true;
 RemoteApp b=new RemoteApp();b.vault.profiles.add(new Profile());b.prefetchSftpOnce();b.prefetchSftpOnce();
 check(SshTransport.attempts==1 && SshTransport.reads==0,"failed startup connection stops without retries");
 check(b.changes==0 && b.sftpSnapshots.isEmpty() && SshTransport.closed==1,"failed prefetch stays quiet and releases transport");
 List<RemoteFiles.Entry> entries=Arrays.asList(
 new RemoteFiles.Entry("z-folder",true,false,0,1,""),
 new RemoteFiles.Entry("a.txt",false,false,2,10,""),
 new RemoteFiles.Entry("b.txt",false,false,20,30,""),
 new RemoteFiles.Entry("c.txt",false,false,40,20,""));
 RemoteFiles f=new RemoteFiles("/work",entries);
 check(f.sorted(0).entries.get(1).name.equals("a.txt"),"name sorting ascending");
 check(f.sorted(1).entries.get(1).name.equals("b.txt"),"date sorting newest first");
 check(f.sorted(2).entries.get(1).name.equals("c.txt"),"size sorting largest first");
 check(f.sorted(1).entries.get(0).directory && f.sorted(2).entries.get(0).directory,"folders remain first in all modes");
 check(RemoteFiles.humanSize(512).equals("0.5 KB"),"sub-KB size shown in KB");
 check(RemoteFiles.humanSize(2048).equals("2.0 KB"),"KB formatting");
 check(RemoteFiles.humanSize(1048576).equals("1.0 MB"),"MB boundary");
 check(RemoteFiles.humanSize(1610612736).equals("1.5 GB"),"GB formatting");
 check(RemoteFiles.humanSize(0).equals("0.0 KB"),"zero size stays honest");
 check(RemoteFiles.isLikelyText("notes.md")&&RemoteFiles.isLikelyText("conf.json")&&RemoteFiles.isLikelyText("Dockerfile"),"known text names preview");
 check(!RemoteFiles.isLikelyText("logo.png")&&!RemoteFiles.isLikelyText("doc.pdf")&&!RemoteFiles.isLikelyText("pic.PNG"),"known binaries skip viewer");
 check(RemoteFiles.isLikelyText("data.zzz"),"unknown extension still tries preview");
 check(!RemoteFiles.isLikelyText("model.onnx")&&!RemoteFiles.isLikelyText("lib.so"),"more binaries skip viewer");
 check(RemoteFiles.isLikelyText("notes.rst")&&RemoteFiles.isLikelyText("app.vue")&&RemoteFiles.isLikelyText("nb.ipynb"),"more text formats preview");
 check(f.entries.get(1).name.equals("a.txt"),"sort does not mutate shared cached snapshot");
 }
}''')
        subprocess.run(['javac','-d',str(folder),str(SRC/'RemoteFiles.java'),str(java)],check=True)
        subprocess.run(['java','-cp',str(folder),'app.remotessh.PrefetchTest'],check=True)
    activity=(SRC/'MainActivity.java').read_text();browser=(SRC/'SftpBrowser.java').read_text()
    settings=method(activity,'  private void showSettings()')
    menu=(SRC/'CenteredMenu.java').read_text()
    assert 'label.setGravity(Gravity.CENTER)' in menu and 'widthPixels*.60f' in menu
    assert 'CenteredMenu.show(this,fg,muted,version,options' in settings and 'getPackageInfo' in settings
    assert 'header.setGravity(Gravity.LEFT' in menu and 'divider(activity,muted' in menu
    assert 'i<=3' in menu and 'i>=7' in menu and 'widthPixels*.60f' in menu
    assert 'private void showMainMenu() { showSettings(); }' in activity
    assert 'ui.button("刷新"' not in browser and 'new PullRefreshScrollView' in browser
    assert 'attachHoldActions' in browser and 'offerSave(full,name,size)' in browser and 'private int sortMode=1;' in browser
    assert 'RemoteFiles.isLikelyText(entry.name)' in browser and 'SftpFileActivity.openExternal(ui.activity,app,full)' in browser
    viewer=(SRC/'SftpFileActivity.java').read_text()
    assert 'Markdown.render(loaded.value,ui.muted,ui.panel)' in viewer and 'isMarkdown(path)' in viewer
    assert 'LinkMovementMethod.getInstance()' in viewer and 'ArrowKeyMovementMethod.getInstance()' in viewer
    assert 'String[] modes={"名称","日期","大小"}' in browser, "sort rows follow the compact menu"
    assert '(i==sortMode?"✓ ":"")' in browser, "current sort marked"
    assert 'probe.connectFiles' in app and 'probe.connect(' not in method(app,'  void prefetchSftpOnce()')
    viewer=(SRC/'SftpFileActivity.java').read_text();manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
    provider=(SRC/'CacheFileProvider.java').read_text()
    assert 'setEnabled(false)' not in viewer and 'setKeyListener(plainKeys)' in viewer,'viewer stays touch-enabled while read-only'
    assert 'offerExternal(reason)' in viewer and 'CacheFileProvider.uriFor(owner,dir,target)' in viewer
    assert 'CachePaths.uniqueRelative' in viewer and 'new File(dir,path.substring' not in viewer
    assert 'FLAG_GRANT_READ_URI_PERMISSION' in viewer
    assert 'CacheFileProvider' in manifest and 'grantUriPermissions' in manifest
    paths=(SRC/'CachePaths.java').read_text()
    assert 'CachePaths.resolve(openDir(getContext()),uri.getPath())' in provider,'provider delegates path checks'
    assert 'canonical.startsWith(rootCanonical + File.separator)' in paths,'traversal guard lives in CachePaths'
    assert '${applicationId}.files' in manifest,'provider authority follows the application id'
    print('PASS centered 60%-width direct menu, sort/pull-refresh and SFTP-only prefetch source wiring')

if __name__=='__main__':main()
