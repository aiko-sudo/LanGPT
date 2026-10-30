package app.remotessh;

/** Per-task elapsed time, never a fabricated completion percentage. */
final class TaskProgress {
  private String turn = "";
  private long started, ended;
  private boolean observed;

  void start(String id, long remoteStartedMillis, long now) {
    if (started == 0 || (ended > 0 && id.isEmpty()) || (!id.isEmpty() && !turn.isEmpty() && !id.equals(turn))) {
      started = remoteStartedMillis > 0 && remoteStartedMillis <= now ? remoteStartedMillis : now;
      observed = remoteStartedMillis <= 0 || remoteStartedMillis > now;
      ended = 0;
    } else if (remoteStartedMillis > 0 && remoteStartedMillis <= now && observed) {
      started = remoteStartedMillis;
      observed = false;
    }
    if (!id.isEmpty()) turn = id;
  }

  void finish(long now) { if (started > 0 && ended == 0) ended = now; }

  String elapsed(long now) {
    if (started == 0) return "执行时间未知";
    long seconds = Math.max(0, (ended > 0 ? ended : now) - started) / 1000;
    String duration = seconds < 60 ? seconds + "秒"
        : seconds < 3600 ? seconds / 60 + "分" + seconds % 60 + "秒"
        : seconds / 3600 + "时" + (seconds % 3600) / 60 + "分";
    return (observed ? "已观察 " : "已执行 ") + duration;
  }
}
