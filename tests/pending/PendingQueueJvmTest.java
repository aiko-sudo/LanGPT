package app.remotessh;

import java.util.*;

/** No Android, model, network or test-runner dependency. Tests production classes directly. */
public final class PendingQueueJvmTest {
  static int checks;
  static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
  static final class MemoryStorage implements PromptTemplateStore.Storage {
    final Map<String,String> values=new HashMap<>();
    public String read(String key){return values.get(key);}
    public void write(String key,String value){values.put(key,value);}
  }
  public static void main(String[] args)throws Exception {
    TaskQueue<List<String>> queue=new TaskQueue<>();
    List<String> draft=new ArrayList<>(Arrays.asList("image-a","file-a"));
    List<String> detached=new ArrayList<>(draft);
    check(queue.add("first",detached));draft.clear();
    check(!queue.sequential());
    queue.add("second",Collections.singletonList("image-b"));
    TaskQueue.Item<List<String>> first=queue.first(), second=queue.snapshot().get(1);
    check(first.attachments.equals(Arrays.asList("image-a","file-a")));
    check(queue.move(second,-1)&&queue.first()==second);
    check(queue.edit(first,"edited")&&first.text.equals("edited"));
    check(queue.move(first,-1)&&queue.first()==first);
    check(queue.begin()==first&&queue.begin()==null);
    check(!queue.edit(first,"bad")&&!queue.move(first,1)&&queue.remove(first)==null);
    queue.accepted(first,"turn-1");check(first.status==TaskQueue.Status.RUNNING);
    queue.enable();queue.observeSafety(true,false,false,true,false,false);
    check(!queue.sequential()&&queue.first()==first&&first.status==TaskQueue.Status.RUNNING);
    queue.enable();queue.observeSafety(true,true,false,false,false,false);check(!queue.sequential());
    queue.enable();queue.observeSafety(true,false,true,false,false,false);check(!queue.sequential());
    queue.enable();queue.observeSafety(true,false,false,false,true,false);check(!queue.sequential());
    queue.enable();queue.observeSafety(true,false,false,false,false,true);check(!queue.sequential());
    queue.completed();check(queue.first()==second);
    TaskQueue.Item<List<String>> removed=queue.remove(second);
    draft.addAll(removed.attachments);
    check(draft.equals(Collections.singletonList("image-b"))&&queue.first()==null);
    check(queue.remove(second)==null); // detached attachment owner can only be returned once
    queue.add("unknown",detached);first=queue.begin();queue.enable();queue.disconnect();
    check(!queue.sequential()&&first.status==TaskQueue.Status.UNKNOWN&&queue.begin()==null);
    queue.enable();queue.unknown(first);check(!queue.sequential()&&queue.begin()==null);
    queue.completed();check(queue.first()==first); // no plausible success after disconnect
    TaskQueue<String> failed=new TaskQueue<>();failed.add("x","attachment");failed.begin();failed.enable();failed.failed();
    check(!failed.sequential()&&failed.first().status==TaskQueue.Status.FAILED&&failed.begin()==null);
    TaskQueue<String> bounded=new TaskQueue<>();
    for(int i=0;i<TaskQueue.LIMIT;i++)check(bounded.add(""+i,""));check(!bounded.add("overflow",""));
    TaskQueue<String> race=new TaskQueue<>();race.add("race","file");
    TaskQueue.Item<String> inFlight=race.begin();
    race.outcome("turn-new",true,false);check(race.first()==inFlight&&race.begin()==null);
    race.accepted(inFlight,"turn-new");check(race.first()==null);
    race.add("next","file");inFlight=race.begin();race.accepted(inFlight,"turn-2");
    race.outcome("turn-new",true,false);check(race.first()==inFlight);
    race.outcome("turn-2",true,true);check(race.first()==inFlight);
    race.outcome("turn-2",true,false);check(race.first()==null);
    race.add("no-id","file");inFlight=race.begin();race.accepted(inFlight,"");
    check(inFlight.status==TaskQueue.Status.UNKNOWN&&race.begin()==null);
    TaskQueueRegistry<String> registry=new TaskQueueRegistry<>();
    registry.get("a:b","c").add("private","file");
    check(registry.get("a","b:c").first()==null);
    check(registry.get("other","c").first()==null);
    check(registry.get("a:b","other-thread").first()==null);
    check(registry.get("a:b","c").first().text.equals("private"));
    registry.remove("other","c");check(registry.get("a:b","c").first()!=null);
    MemoryStorage storage=new MemoryStorage();
    PromptTemplateStore a=new PromptTemplateStore(storage,"profile-a","codex","/work/./project/../project/");
    PromptTemplateStore same=new PromptTemplateStore(storage,"profile-a","codex","/work/project");
    PromptTemplateStore b=new PromptTemplateStore(storage,"profile-b","codex","/work/project");
    PromptTemplateStore runtime=new PromptTemplateStore(storage,"profile-a","claude","/work/project");
    PromptTemplateStore project=new PromptTemplateStore(storage,"profile-a","codex","/work/other");
    a.save(null,"审阅","检查当前改动");check(same.list().size()==1);
    check(b.list().isEmpty()&&runtime.list().isEmpty()&&project.list().isEmpty());
    PromptTemplateStore.Template t=a.list().get(0);a.save(t.id,"编辑","新内容");
    check(a.list().size()==1&&a.list().get(0).text.equals("新内容"));
    check(PromptTemplateStore.fill("draft","template",true).equals("draft\ntemplate"));
    check(PromptTemplateStore.fill("draft","template",false).equals("template"));
    PromptTemplateStore reloaded=new PromptTemplateStore(storage,"profile-a","codex","/work/project");
    check(reloaded.list().get(0).id.equals(t.id));a.delete(t.id);check(reloaded.list().isEmpty());
    check(!a.key.contains("profile-a")&&!a.key.contains("/work"));
    boolean refused=false;try{new PromptTemplateStore(storage,"p","codex","relative");}catch(IllegalArgumentException e){refused=true;}check(refused);
    storage.values.put(a.key,"invalid");refused=false;try{a.list();}catch(java.io.IOException e){refused=true;}check(refused);
    System.out.println("PASS "+checks+" checks");
  }
}
