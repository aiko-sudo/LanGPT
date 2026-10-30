"""Run production picker load/display/sort methods using UI/network adapters on JVM.
Validates control labels, complete contents and late-result guards, not device layout.
"""
from pathlib import Path
import subprocess
import tempfile
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'app/src/main/java/app/remotessh'

def main():
    source=(SRC/'DirectoryPicker.java').read_text()
    methods='\n'.join(method(source,s) for s in ['  private String displayedPath()', '  private void loading()',
      '  private void load(String path)', '  private void display(RemoteFiles result)', '  private void resort()',
      '  private String sortLabel(', '  private void sortBarRefresh('])
    up_line=next(line.strip() for line in source.splitlines() if 'up = button("上一级"' in line)
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='directory-picker-test-', ignore_cleanup_errors=True) as folder:
        folder=Path(folder)
        view=folder/'android/view/View.java';view.parent.mkdir(parents=True);view.write_text('package android.view;public class View {public static final int VISIBLE=0,GONE=8;}')
        widget=folder/'android/widget/TextView.java';widget.parent.mkdir(parents=True);widget.write_text('''package android.widget;
public class TextView {String text="";public boolean enabled=true;public Runnable click;
public void setText(String t){text=t;}public CharSequence getText(){return text;}
public void setEnabled(boolean e){enabled=e;}public void setAlpha(float f){}public void setVisibility(int v){}}''')
        alert=folder/'android/app/AlertDialog.java';alert.parent.mkdir(parents=True);alert.write_text('''package android.app;
public class AlertDialog {public static class Builder {public Builder(Object context){}public Builder setTitle(String s){return this;}
public Builder setMessage(String s){return this;}public Builder setPositiveButton(String s,java.util.function.BiConsumer<Object,Integer> action){return this;}
public void show(){}}}''')
        test=folder/'PickerTest.java'
        test.write_text('''package app.remotessh;
import java.util.*;import java.util.function.*;import android.widget.TextView;import android.view.View;import android.app.AlertDialog;
class Pending {String path;Consumer<RemoteFiles> success;Consumer<String> failure;Pending(String p,Consumer<RemoteFiles>s,Consumer<String>f){path=p;success=s;failure=f;}}
class App {List<Pending> pending=new ArrayList<>();void browseProjectDirectory(String p,Consumer<RemoteFiles>s,Consumer<String>f){pending.add(new Pending(p,s,f));}}
class Ui {Object activity=new Object();void clickable(TextView view,Runnable click){view.click=click;}TextView button(String s,boolean p,Runnable c){TextView v=new TextView();v.setText(s);v.click=c;return v;}}
class Rows {void removeAllViews(){}void addView(TextView row){}}
class Dialog {boolean showing=true;boolean isShowing(){return showing;}}
class Picker {
 App app=new App();Ui ui=new Ui();Dialog dialog=new Dialog();Rows list=new Rows();
 TextView pathLabel=new TextView(),status=new TextView(),choose=new TextView(),up,create=new TextView(),nameCol=new TextView(),dateCol=new TextView(),input=new TextView();
 RemoteDirectory directory;RemoteFiles contents;String requestedPath="";int request,visible,sortMode=3;
 List<RemoteFiles.Entry> ordered=new ArrayList<>();List<String> shown=new ArrayList<>();
 Picker(){input.setText("输入路径");'''+up_line+'''}
 TextView button(String text,boolean primary,Runnable action){return ui.button(text,primary,action);}
 void clearStatusAction(){status.click=null;}void appendPage(){shown.clear();for(RemoteFiles.Entry e:ordered)shown.add(e.name);}
'''+methods+'''
 void displayTest(RemoteFiles result){display(result);}void open(String path){load(path);}void sort(int mode){sortMode=mode;resort();}
}
public class PickerTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 static RemoteFiles files(String path){return new RemoteFiles(path,Arrays.asList(
  new RemoteFiles.Entry("z-folder",true,false,0,20,""),new RemoteFiles.Entry("notes.txt",false,false,12,30,""),new RemoteFiles.Entry(".hidden",false,false,1,10,"")));}
 public static void main(String[]args){
 Picker p=new Picker();p.open("/work/project");
 check(p.app.pending.get(0).path.equals("/work/project") && p.pathLabel.getText().toString().equals("/work/project"),"default current path immediately requested and shown");
 check(!p.choose.enabled && p.up.enabled,"loading blocks selection but leaves parent escape available");
 p.app.pending.get(0).success.accept(files("/work/project"));
 check(p.shown.size()==3 && p.shown.contains("notes.txt") && p.shown.contains(".hidden"),"folder contents include regular and hidden files, not just subdirectories");
 check(p.shown.equals(Arrays.asList("notes.txt","z-folder",".hidden")),"default date descending mixes files and folders");
 p.displayTest(new RemoteFiles("/work/project",Arrays.asList(new RemoteFiles.Entry("z",true,false,0,0,""),new RemoteFiles.Entry("b",true,false,0,20,""),new RemoteFiles.Entry("a",false,false,0,20,""),new RemoteFiles.Entry("missing",false,false,0,-1,""))));
 check(p.shown.equals(Arrays.asList("a","b","missing","z")),"equal dates use name and missing dates last");
 p.sort(2);check(p.shown.equals(Arrays.asList("a","b","missing","z")),"ascending keeps missing dates last");
 p.sort(3);p.sort(1);
 check(p.up.getText().toString().equals("上一级") && p.input.getText().toString().equals("输入路径"),"sorting never overwrites toolbar button labels");
 p.up.click.run();check(p.app.pending.get(1).path.equals("/work"),"parent button issues actual parent directory read");
 p.open("/");p.app.pending.get(2).success.accept(files("/"));check(!p.up.enabled,"root parent button disabled");
 p.open("/a");p.open("/b");p.app.pending.get(3).success.accept(files("/a"));
 check(p.pathLabel.getText().toString().equals("/b"),"late old folder result cannot steal new navigation");
 p.app.pending.get(4).success.accept(files("/b"));check(p.choose.enabled && p.directory.path.equals("/b"),"only loaded canonical directory can be selected");
 p.open("/no-access/child");p.app.pending.get(5).failure.accept("denied");
 check(!p.choose.enabled && p.up.enabled,"permission error keeps parent navigation available and selection disabled");
 p.up.click.run();check(p.app.pending.get(6).path.equals("/no-access"),"parent navigation works after failed initial directory");
 p.dialog.showing=false;p.app.pending.get(6).success.accept(files("/no-access"));check(!p.directory.path.equals("/no-access"),"closed picker ignores late response");
 }
}''')
        subprocess.run(['javac','-d',str(folder),str(view),str(widget),str(alert),str(SRC/'RemoteFiles.java'),str(SRC/'RemoteDirectory.java'),str(test)],check=True)
        subprocess.run(['java','-cp',str(folder),'app.remotessh.PickerTest'],check=True)
    assert 'getChildCount() == 2' not in source and 'sortBarRefresh(nameCol,dateCol)' in source
    assert 'if(entry.directory || entry.link)load(' in source and '这是文件，请选择文件夹作为项目' in source
    assert '使用此文件夹' in source and '新建文件夹' in source and 'load(result.path)' in source
    controller=(SRC/'DirectoryController.java').read_text();assert 'current.listFiles(path)' in controller
    print('PASS exact sort-label references, nonselectable regular files and actual SFTP browse/create wiring')
if __name__=='__main__':main()
