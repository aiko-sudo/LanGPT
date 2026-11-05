package app.remotessh;

import java.util.*;

/** Main-thread directory results: late checks cannot override newer checks. */
final class ProjectHistoryFilter {
  private final Map<String, Boolean> known = new HashMap<>();
  private final Map<String, Integer> versions = new HashMap<>();
  private int sequence;
  Map<String, Integer> begin(Collection<String> paths) {
    Map<String, Integer> tickets = new LinkedHashMap<>();
    for (String path : paths) if (path != null && path.startsWith("/")) {
      int version = ++sequence;
      versions.put(path, version); tickets.put(path, version);
    }
    return tickets;
  }
  boolean finish(Map<String, Integer> tickets, Map<String, Boolean> result) {
    boolean changed = false;
    for (Map.Entry<String, Integer> entry : tickets.entrySet()) {
      String path = entry.getKey();
      if (!entry.getValue().equals(versions.get(path))) continue;
      Boolean value = result == null ? null : result.get(path);
      Boolean previous = value == null ? known.remove(path) : known.put(path, value);
      if (!Objects.equals(previous, value)) changed = true;
    }
    return changed;
  }
  boolean missing(String path) { return Boolean.FALSE.equals(known.get(path)); }
  void clear() { known.clear(); versions.clear(); sequence++; }
}
