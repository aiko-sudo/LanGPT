package app.remotessh;

import java.util.*;

/** An observed terminal-turn snapshot, not a claim that the user's goal passed. */
final class TaskResult {
  final String turn, status, error, summary;
  final List<String> paths, artifacts, commands;
  final String version;

  TaskResult(String turn, String status, String error, List<ChatItem> items) {
    this.status=status;this.error=error==null?"":error;
    String resolved=turn==null?"":turn;
    if(resolved.isEmpty() && !items.isEmpty()) resolved="unknown:"+items.get(items.size()-1).id;
    this.turn=resolved;
    LinkedHashSet<String> changed=new LinkedHashSet<>(), produced=new LinkedHashSet<>();
    List<String> executions=new ArrayList<>();
    String reply="";
    int start=0;
    for(int i=items.size()-1;i>=0;i--) if(items.get(i).user()){start=i;break;}
    for(int i=start;i<items.size();i++) {
      ChatItem item=items.get(i);
      if(turn!=null && !turn.isEmpty() && !item.turnId.isEmpty() && !turn.equals(item.turnId))continue;
      if(item.type.equals("agentMessage") && !item.text.trim().isEmpty())reply=item.text.trim();
      changed.addAll(item.changedPaths);
      produced.addAll(item.artifactPaths);
      if(item.type.equals("fileChange") && item.changedPaths.isEmpty()) {
        boolean header=true;
        for(String line:item.detail.split("\n")) {
          if(line.startsWith("diff --git "))header=true;
          if(line.startsWith("@@"))header=false;
          if(header && (line.startsWith("+++ ") || line.startsWith("--- "))) {
            String path=line.substring(4).split("\t",2)[0];
            if(path.equals("/dev/null"))continue;
            if(path.startsWith("a/") || path.startsWith("b/"))path=path.substring(2);
            if(!path.isEmpty())changed.add(path);
          }
        }
      }
      if(item.type.equals("commandExecution")) {
        String command=item.text.replace('\n',' ').trim();
        if(command.length()>240)command=command.substring(0,240)+"…";
        String observed=item.exitCode==null ? "未收到退出码（"+item.status+"）" : "退出码 "+item.exitCode;
        executions.add(command+" · "+observed);
      }
    }
    summary=reply.length()>12000?reply.substring(0,12000)+"\n（摘要已截取，完整内容见会话）":reply;
    paths=Collections.unmodifiableList(new ArrayList<>(changed));
    artifacts=Collections.unmodifiableList(new ArrayList<>(produced));
    commands=Collections.unmodifiableList(executions);
    // Content equality, not token counts or a random arrival timestamp, drives unread state.
    version=this.turn+"\n"+details();
  }

  boolean failed(){return status.equals("failed") || !error.isEmpty();}
  String label(){return failed()?"回合失败":status.equals("interrupted")?"已停止":status.equals("completed")?"回合已结束":"结束状态待确认";}
  String preview(){
    String text=summary.isEmpty()?(error.isEmpty()?"未收到最终回复，点击查看执行记录":error):summary;
    return text.length()>180?text.substring(0,180)+"…":text;
  }
  String details(){
    StringBuilder text=new StringBuilder(label()).append("（不代表目标已验收）\n\n结果摘要\n")
        .append(summary.isEmpty()?"未收到最终回复":summary);
    if(!error.isEmpty())text.append("\n\n错误\n").append(error);
    text.append("\n\n改动文件\n").append(paths.isEmpty()?"未收到结构化文件变更":String.join("\n",paths));
    text.append("\n\n已观察到的执行\n").append(commands.isEmpty()?"未验证：未收到命令执行记录":String.join("\n",commands));
    text.append("\n命令退出不等于测试或目标验收通过。");
    text.append("\n\n产物路径\n").append(artifacts.isEmpty()?"未收到明确的结构化产物路径":String.join("\n",artifacts));
    return text.toString();
  }
}
