package app.remotessh;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import java.util.*;
import java.util.function.Consumer;

/** Read-only configuration picker: selecting is separate from editing. */
final class ConnectionPicker {
  static String agent(String runtime) {
    switch(runtime) { case "claude": return "CC"; case "opencode": return "OpenCode";
      case "dsh": return "DSH"; default: return "Codex"; }
  }
  static void show(Activity activity, RemoteApp app, int fg, int muted,
      Consumer<Profile> connect, Consumer<Profile> edit, Profile previous) {
    List<Profile> profiles = new ArrayList<>(app.vault.profiles);
    Profile current = app.profile;
    List<CharSequence> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    for (Profile p : profiles) {
      boolean selected = current != null && current.id.equals(p.id);
      String heading = (selected ? "✓ " : "") + p.title() + " · " + agent(p.runtime);
      String address = p.user + "@" + p.host + ":" + p.port;
      if (selected) address += app.connected ? " · 已连接" : app.connecting ? " · 连接中" : " · 未连接";
      SpannableString label = new SpannableString(heading + "\n" + address);
      label.setSpan(new RelativeSizeSpan(.75f), heading.length()+1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      label.setSpan(new ForegroundColorSpan(muted), heading.length()+1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      labels.add(label);
      actions.add(() -> connect.accept(p));
    }
    if (current != null && !app.connected && !app.connecting) {
      labels.add("重试当前连接"); actions.add(() -> connect.accept(current));
      if (previous != null && !previous.id.equals(current.id)) {
        labels.add("返回原连接 · " + previous.title()); actions.add(() -> connect.accept(previous));
      }
    }
    if (current != null) {
      labels.add("编辑当前连接"); actions.add(() -> edit.accept(current));
    } else if (!profiles.isEmpty()) {
      Profile first=profiles.get(0);
      labels.add("编辑连接"); actions.add(() -> edit.accept(first));
    }
    if (labels.isEmpty()) { edit.accept(new Profile()); return; }
    CenteredMenu.show(activity,fg,muted,"",labels.toArray(new CharSequence[0]),i -> actions.get(i).run());
  }
}
