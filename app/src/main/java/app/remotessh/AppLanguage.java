package app.remotessh;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.LocaleList;
import java.util.*;

/** App-wide display preference. Never changes protocol, stored messages or JVM locale. */
final class AppLanguage implements Application.ActivityLifecycleCallbacks {
  static final String[] TAGS = {"", "zh-Hans", "en"};
  static final String[] NAMES = {"", "简体中文", "English"};
  private final RemoteApp app;
  private final Set<Activity> activities = Collections.newSetFromMap(new WeakHashMap<>());

  AppLanguage(RemoteApp app) {
    this.app = app;
    refresh(app);
    app.registerActivityLifecycleCallbacks(this);
  }

  static String preference(Context context) {
    String tag = context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", "");
    return Arrays.asList(TAGS).contains(tag) ? tag : "";
  }

  static Locale supported(Locale locale) {
    // This fork is Chinese-first: any system locale that is not explicitly English falls back to Chinese.
    String language = locale.getLanguage();
    if (language.equals("zh")) return Locale.SIMPLIFIED_CHINESE;
    if (language.equals("en")) return Locale.ENGLISH;
    return Locale.SIMPLIFIED_CHINESE;
  }

  static Locale locale(Context context) {
    String tag = preference(context);
    if (!tag.isEmpty()) return Locale.forLanguageTag(tag.equals("zh-Hans") ? "zh-Hans" : "en");
    LocaleList system = android.content.res.Resources.getSystem().getConfiguration().getLocales();
    for (int i = 0; i < system.size(); i++) {
      String lang = system.get(i).getLanguage();
      if (lang.equals("zh") || lang.equals("en")) return supported(system.get(i));
    }
    return Locale.SIMPLIFIED_CHINESE;
  }

  static Context wrap(Context base) {
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.setLocales(new LocaleList(locale(base)));
    return base.createConfigurationContext(config);
  }

  static void refresh(Context app) { UiText.initialize(wrap(app)); }

  void select(String tag) {
    if (!Arrays.asList(TAGS).contains(tag) || tag.equals(preference(app))) return;
    app.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", tag).apply();
    refresh(app);
    notifyPresentation();
    for (Activity activity : new ArrayList<>(activities)) {
      // Terminal and file editor own live sessions/unsaved work; never recreate them.
      // They apply the selected locale when opened again.
      if (activity instanceof SshTerminalActivity || activity instanceof SftpFileActivity) continue;
      if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
    }
  }

  void notifyPresentation() {
    // A locale change is not a conversation event or an authorization event.
    if (app.observer != null) app.observer.run();
    for (Runnable listener : new ArrayList<>(app.stateListeners)) listener.run();
  }

  @Override public void onActivityCreated(Activity activity, Bundle state) { activities.add(activity); }
  @Override public void onActivityDestroyed(Activity activity) { activities.remove(activity); }
  @Override public void onActivityStarted(Activity activity) {}
  @Override public void onActivityResumed(Activity activity) {}
  @Override public void onActivityPaused(Activity activity) {}
  @Override public void onActivityStopped(Activity activity) {}
  @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
