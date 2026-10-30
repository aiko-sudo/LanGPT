package app.remotessh;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Shared native full-screen structure for project and directory navigation. */
final class RemoteScreens {
  final Activity activity;
  final int bg, fg, panel, muted;

  RemoteScreens(Activity activity, boolean dark) {
    this.activity = activity;
    // iOS design tokens (HIG): systemGroupedBackground / label / secondaryLabel / systemBlue
    bg = dark ? Color.BLACK : Color.WHITE;
    fg = dark ? 0xfff2f2f7 : 0xff1c1c1e;
    panel = dark ? 0xff1c1c1e : 0xfff2f2f7;
    muted = dark ? 0xff8e8e93 : 0xff8e8e93;
  }

  int dp(float n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(activity);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView text(String value, int size) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(fg);
    return t;
  }

  GradientDrawable rounded(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(28));
    return d;
  }

  TextView button(String value, boolean primary, Runnable click) {
    TextView t = text(value, 16);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(50));
    t.setPadding(dp(18), dp(12), dp(18), dp(12));
    t.setBackground(rounded(primary ? 0xff007aff : panel));
    if (primary) t.setTextColor(Color.WHITE);
    PressFeedback.apply(t, bg == Color.BLACK);
    t.setOnClickListener(v -> click.run());
    return t;
  }

  IconView icon(String name, String label, Runnable click) {
    IconView i = new IconView(activity, name, fg);
    i.setContentDescription(label);
    i.setBackground(rounded(panel));
    PressFeedback.apply(i, bg == Color.BLACK);
    i.setOnClickListener(v -> click.run());
    return i;
  }

  void clickable(View view, Runnable click) {
    PressFeedback.apply(view, bg == Color.BLACK);
    view.setOnClickListener(v -> click.run());
  }

  Dialog window(LinearLayout root) {
    // Explicit non-floating window: default Dialog themes retain decor/minimum
    // insets on some phones even after setLayout(MATCH_PARENT, MATCH_PARENT).
    Dialog d = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
    root.setBackgroundColor(bg);
    d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
    Window w = d.getWindow();
    if (w != null) {
      // Immersive sheet: solid background behind the system-bar insets so the
      // page visually spans the whole screen (content stays inside safe area).
      w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
      w.getDecorView().setPadding(0, 0, 0, 0);
      w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setGravity(Gravity.FILL);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener(
            (v, insets) -> {
              android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
              android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
              root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
              return WindowInsets.CONSUMED;
            });
      } else root.setFitsSystemWindows(true);
      // Full-screen sheet (HIG style): these pages own the whole screen
      // instead of a floating wrap-content card.
      w.setLayout(-1, -1);
    }
    return d;
  }

  void show(Dialog dialog) {
    dialog.show();
    dialog.getWindow().setLayout(-1, -1);
  }

  TextView heading(LinearLayout header, String value) {
    TextView t = text(value, 21);
    t.setTypeface(null, Typeface.BOLD);
    t.setGravity(Gravity.CENTER);
    header.addView(t, new LinearLayout.LayoutParams(0, dp(56), 1));
    return t;
  }
}
