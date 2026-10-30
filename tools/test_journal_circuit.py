"""Compile the real journal polling method; deterministic queued-worker tests."""
from pathlib import Path
import subprocess, tempfile, os
from test_history_navigation import method
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.org_json_jar(),'org.json'))
poll=method((SRC/'RemoteApp.java').read_text(),'  void pollConversationJournals()')
java=r'''package app.remotessh;
import java.util.*;import org.json.*;import java.io.*;
class Queue {ArrayDeque<Runnable> q=new ArrayDeque<>();void execute(Runnable r){q.add(r);}void post(Runnable r){q.add(r);}void drain(){while(!q.isEmpty())q.remove().run();}}
class Json {static JSONObject obj(Object... kv){JSONObject j=new JSONObject();for(int i=0;i<kv.length;i+=2)j.put(kv[i].toString(),kv[i+1]);return j;}}
class SshTransport {int calls;boolean fail;List<String> paths=new ArrayList<>();JSONObject jsonCommand(String s,JSONObject p,int t)throws Exception{calls++;paths.add(p.getString("path"));if(fail)throw new IOException("channel is not opened");return new JSONObject();}}
class Profile {}
class Assets {InputStream open(String s){return new ByteArrayInputStream(new byte[0]);}}
class MediaFiles {static void copy(InputStream i,OutputStream o,long n){}}
class ConversationJournal {long offset;JSONObject context;void apply(JSONObject j,String id){}}
class InteractionHistory {Map<String,Object> entries=new HashMap<>();long revision;void archive(JSONObject j,String id){}}
class PermissionMode {static PermissionMode fromRemote(Object o,JSONObject j){return null;}}
class RemoteApp {
 boolean connected=true;SshTransport transport=new SshTransport();Profile profile=new Profile();
 int generation=1,journalPollFailures,journalPollCursor;long statusRevision,journalRetryAt;
 final Set<String> journalPolling=new HashSet<>();String journalPath="",journalScript="fixture";
 ConversationJournal journal=new ConversationJournal();InteractionHistory interactions=new InteractionHistory();
 PermissionMode effectivePermissionMode;Queue worker=new Queue(),main=new Queue();
 List<String> ids=new ArrayList<>();RemoteApp(){for(int i=0;i<20;i++)ids.add("t"+i);}
 Collection<String> conversationIds(){return ids;}Assets getAssets(){return new Assets();}
 void inConversation(String id,Runnable r){String old=journalPath; journalPath="/"+id+".jsonl";try{r.run();}finally{journalPath=old;}}
 void syncJournalQuestions(){}void saveInteractions(){}void changed(){}
 void finish(){worker.drain();main.drain();}
 void reconnect(){generation++;transport=new SshTransport();journalPolling.clear();journalPollFailures=0;journalRetryAt=0;journalPollCursor=0;}
''' + poll + r'''
}
public class JournalCircuitTest {
 static int checks;static void check(boolean b,String n){if(!b)throw new AssertionError(n);checks++;System.out.println("PASS "+n);}
 public static void main(String[] args){
 RemoteApp a=new RemoteApp();a.pollConversationJournals();
 check(a.worker.q.size()==1 && a.journalPolling.size()==1,"20 conversations schedule only one exec");
 a.pollConversationJournals();check(a.worker.q.size()==1,"timer tick cannot overlap in-flight exec");
 a.finish();check(a.journalPolling.isEmpty(),"success releases in-flight slot");
 a.pollConversationJournals();a.finish();check(!a.transport.paths.get(0).equals(a.transport.paths.get(1)),"round robin advances to another conversation");
 a.transport.fail=true;
 for(int i=1;i<=3;i++){a.journalRetryAt=0;a.pollConversationJournals();a.finish();check(a.journalPollFailures==i && a.journalPolling.isEmpty(),"failure "+i+" updates state and releases slot");}
 int calls=a.transport.calls;a.pollConversationJournals();a.finish();check(a.transport.calls==calls,"backoff blocks immediate channel retries");
 check(a.journalRetryAt-System.currentTimeMillis()>25000,"three failures impose bounded 30-second cooldown");
 a.journalRetryAt=0;a.transport.fail=false;a.pollConversationJournals();a.finish();check(a.journalPollFailures==0 && a.journalRetryAt==0,"cooldown expiry resumes and success resets failures");
 a.pollConversationJournals();a.worker.drain();a.reconnect();a.pollConversationJournals();a.main.drain();check(a.journalPolling.size()==1,"late old-generation success cannot clear new-generation slot");
 a.finish();check(a.journalPolling.isEmpty(),"new generation success releases its own slot");
 a.transport.fail=true;a.pollConversationJournals();a.worker.drain();a.reconnect();a.pollConversationJournals();a.main.drain();check(a.journalPollFailures==0 && a.journalPolling.size()==1,"late failure cannot poison new connection");a.finish();
 a.connected=false;a.pollConversationJournals();check(a.worker.q.isEmpty(),"disconnected application opens no channels");
 System.out.println("TOTAL "+checks);
 }
}
'''
with tempfile.TemporaryDirectory(prefix='journal-regression-', ignore_cleanup_errors=True) as d:
 p=Path(d)/'JournalCircuitTest.java';p.write_text(java)
 subprocess.run(['javac','-cp',str(JAR),'-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d+os.pathsep+str(JAR),'app.remotessh.JournalCircuitTest'],check=True,timeout=20)
