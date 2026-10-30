package app.remotessh;

import java.util.LinkedHashMap;
import java.util.List;

/** Per-connection/thread attention state; history replay never invents new results. */
final class TaskInbox {
  private static final class Entry { String active="", read="";TaskResult result; }
  private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
  private String key(String profile,String thread){return profile+"\n"+thread;}
  private Entry entry(String profile,String thread){
    String key=key(profile,thread);Entry entry=entries.get(key);
    if(entry==null){entry=new Entry();entries.put(key,entry);}
    while(entries.size()>128)entries.remove(entries.keySet().iterator().next());
    return entry;
  }
  void remove(String profile,String thread){entries.remove(key(profile,thread));}
  void started(String profile,String thread,String turn,boolean historical){
    if(historical || thread.isEmpty())return;
    entry(profile,thread).active=turn;
  }
  void completed(String profile,String thread,String turn,String status,String error,List<ChatItem> items,boolean historical){
    if(historical || thread.isEmpty())return;
    Entry entry=entry(profile,thread);
    if(!entry.active.isEmpty() && !turn.isEmpty() && !entry.active.equals(turn))return;
    entry.result=new TaskResult(turn,status,error,items);
  }
  TaskResult result(String profile,String thread){
    Entry e=entries.get(key(profile,thread));
    return e==null || e.result==null || !e.active.isEmpty() && !e.active.equals(e.result.turn) ? null : e.result;
  }
  boolean unread(String profile,String thread){Entry e=entries.get(key(profile,thread));TaskResult result=result(profile,thread);return e!=null && result!=null && !result.version.equals(e.read);}
  void read(String profile,String thread){Entry e=entries.get(key(profile,thread));if(e!=null && e.result!=null)e.read=e.result.version;}
  void read(String profile,String thread,TaskResult viewed){
    Entry e=entries.get(key(profile,thread));
    if(e!=null && e.result!=null && e.result.version.equals(viewed.version))e.read=viewed.version;
  }
  boolean attention(String profile,String thread,boolean running,boolean approval,String error){
    TaskResult result=result(profile,thread);
    return approval || !error.isEmpty() || (!running && (unread(profile,thread) || result!=null && result.failed()));
  }
  int rank(String profile,String thread,boolean running,boolean approval,String error){
    if(approval)return 0;
    TaskResult result=result(profile,thread);
    if(!error.isEmpty() || !running && result!=null && result.failed())return 1;
    if(!running && unread(profile,thread))return 2;
    return running?3:4;
  }
}
