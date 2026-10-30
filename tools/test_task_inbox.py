"""Run actual TaskInbox/TaskResult/ChatItem production classes on JVM with org.json."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))

def main():
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='task-inbox-test-', ignore_cleanup_errors=True) as directory:
        directory=Path(directory);test=directory/'TaskInboxTest.java'
        test.write_text('''package app.remotessh;
import java.util.*;import org.json.*;
public class TaskInboxTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 static ChatItem item(String id,String type,String text,String turn){ChatItem i=new ChatItem(id,type,text);i.turnId=turn;return i;}
 public static void main(String[] args){
 TaskInbox inbox=new TaskInbox();
 List<ChatItem> a=new ArrayList<>();a.add(item("user-a","userMessage","request A","a1"));
 a.add(item("reply-a","agentMessage","Finished A","a1"));
 ChatItem cmd=ChatItem.from(Json.obj("id","cmd-a","type","commandExecution","command","run-check","status","completed","exitCode",0));cmd.turnId="a1";a.add(cmd);
 ChatItem change=ChatItem.from(Json.obj("id","edit-a","type","fileChange","changes",new JSONArray().put(Json.obj("path","src/A.java","diff","patch"))));change.turnId="a1";a.add(change);
 ChatItem artifact=ChatItem.from(Json.obj("id","image-a","type","imageGeneration","savedPath","/work/plot.png"));artifact.turnId="a1";a.add(artifact);
 inbox.started("p","A","a1",false);inbox.started("p","B","b1",false);
 inbox.completed("p","A","a1","completed","",a,false);
 check(inbox.unread("p","A") && !inbox.unread("p","B"),"A completion creates unread only for A");
 check(inbox.rank("p","B",true,true,"")<inbox.rank("p","A",false,false,""),"approval outranks completed unread");
 check(inbox.attention("p","A",false,false,"") && !inbox.attention("p","B",true,false,""),"attention filters actionable tasks not ordinary running work");
 TaskResult r=inbox.result("p","A");
 check(r.summary.equals("Finished A") && r.paths.equals(Arrays.asList("src/A.java")),"summary and structured changed paths reflect actual turn");
 check(r.commands.get(0).contains("退出码 0") && r.details().contains("不等于测试或目标验收通过"),"real exit code is shown without inventing acceptance");
 check(r.artifacts.equals(Arrays.asList("/work/plot.png")),"only explicit produced image path becomes artifact");
 inbox.read("p","A");inbox.completed("p","A","a1","completed","",a,false);
 check(!inbox.unread("p","A"),"read result remains read after duplicate completion event");
 inbox.completed("p","B","old","completed","",a,true);
 check(inbox.result("p","B")==null,"historical replay does not invent newly completed work");
 inbox.completed("other-profile","A","a1","completed","",a,false);
 check(!inbox.unread("p","A") && inbox.unread("other-profile","A"),"read states isolated between connection profiles");
 inbox.started("p","A","a2",false);
 inbox.completed("p","A","a1","failed","late old error",a,false);
 check(inbox.result("p","A")==null && !inbox.unread("p","A"),"late previous-turn completion cannot masquerade as active-turn result");
 List<ChatItem> next=new ArrayList<>(a);next.add(item("user-a2","userMessage","new request","a2"));next.add(item("reply-a2","agentMessage","new result","a2"));
 inbox.completed("p","A","a2","failed","explicit failure",next,false);r=inbox.result("p","A");
 check(r.failed() && inbox.unread("p","A") && r.paths.isEmpty() && r.commands.isEmpty(),"new failed turn unread, no previous-turn files/commands leak");
 inbox.read("p","A");check(inbox.attention("p","A",false,false,""),"read failure stays actionable until superseded");
 TaskResult empty=new TaskResult("empty","completed","",Collections.emptyList());
 check(empty.details().contains("未验证") && empty.artifacts.isEmpty(),"missing checks or artifact outputs never fabricated");
 ChatItem unknown=ChatItem.from(Json.obj("id","cmd","type","commandExecution","status","completed"));
 check(unknown.exitCode==null,"tool completion does not become invented zero exit code");
 TaskResult stopped=new TaskResult("stop","interrupted","",Collections.emptyList());
 check(stopped.label().equals("已停止"),"interrupted turn is not labelled success");
 next.get(next.size()-1).text="revised final result";inbox.completed("p","A","a2","failed","explicit failure",next,false);
 check(inbox.unread("p","A"),"genuinely revised result becomes unread again");
 inbox.read("p","A",r);check(inbox.unread("p","A"),"viewing old snapshot cannot clear newer result unread");
 }
}''')
        sources=[str(SRC/name) for name in ['Json.java','ChatItem.java','TaskResult.java','TaskInbox.java']]
        subprocess.run(['javac','-cp',str(JAR),'-d',str(directory),*sources,str(test)],check=True)
        subprocess.run(['java','-cp',str(directory)+':'+str(JAR),'app.remotessh.TaskInboxTest'],check=True)
    app=(SRC/'RemoteApp.java').read_text();panel=(SRC/'WorkPanel.java').read_text()
    assert 'taskInbox.completed(' in app and 'new ArrayList<>(resultItems.values()), historical)' in app
    assert 'taskInbox.started(' in app and 'd.turnId = p.optString("turnId", turnId)' in app
    assert '"全部", "待处理", "运行中"' in panel
    assert 'taskInbox.read(profileId,task.threadId,result)' in panel and 'result.details()' in panel
    print('PASS per-conversation event/result UI source wiring (not Android interaction tests)')
if __name__=='__main__':main()
