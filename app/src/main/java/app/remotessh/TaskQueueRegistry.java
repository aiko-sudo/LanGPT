package app.remotessh;

import java.util.*;

/** Profile/thread keys use length framing, never hosts or credentials. */
final class TaskQueueRegistry<T> {
  private final Map<String,TaskQueue<T>> queues=new HashMap<>();
  private String key(String profile,String thread){return profile.length()+":"+profile+thread;}
  TaskQueue<T> get(String profile,String thread){return queues.computeIfAbsent(key(profile,thread),k->new TaskQueue<>());}
  TaskQueue<T> find(String profile,String thread){return queues.get(key(profile,thread));}
  void remove(String profile,String thread){queues.remove(key(profile,thread));}
  Collection<TaskQueue<T>> values(){return queues.values();}
}
