package app.remotessh;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Model/effort/speed picker; owns and releases its catalog listener with its window. */
final class ModelPicker {
  private final Activity activity;
  private final RemoteApp app;
  private final DialogUi ui;
  private final boolean wide;
  private Dialog dialog;
  private Runnable catalogListener;

  ModelPicker(Activity activity, RemoteApp app, DialogUi ui, boolean wide) {
    this.activity = activity; this.app = app; this.ui = ui; this.wide = wide;
  }

  private void releaseListener() {
    if (catalogListener != null) {
      app.stateListeners.remove(catalogListener);
      catalogListener = null;
    }
  }

  void dismiss() {
    releaseListener();
    if (dialog != null) dialog.dismiss();
  }

  void show() {
    if (!app.connected) { ui.toast(UiText.get(R.string.network_text_001)); return; }
    final String[] selected = {app.model, app.reasoningEffort, app.serviceTier};
    final Profile modelProfile = app.profile;
    final String modelThread = app.threadId;
    // Custom channel: the list shows EVERY configured API entry (name + model);
    // picking one makes the backend rewrite the provider config for that entry
    // and reconnect — the picker stays a plain model list for the user.
    final List<CustomApiConfig.Entry> customEntries;
    {
      List<CustomApiConfig.Entry> found = null;
      if (app.customOverride) {
        found = new ArrayList<>();
        for (CustomApiConfig.Entry e : app.customApi().entries) if (e.enabled) found.add(e);
        if (found.isEmpty()) found = null; // fall back to the remote catalog
      }
      customEntries = found;
    }
    LinearLayout panelView = ui.column();
    panelView.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8));
    TextView subtitle = ui.text(UiText.get(R.string.network_text_002), 12, ui.muted);
    subtitle.setPadding(ui.dp(4), 0, 0, ui.dp(16));
    panelView.addView(subtitle);
    // Cross-channel switch: jump between the primary (ChatGPT) channel and the
    // custom-API channel from the same picker. The phone shows the model list of
    // the *current* runtime only, so without this there is no obvious way to
    // reach the other channel while the primary still has quota. Both
    // directions carry the current chat history/context with them.
    boolean onCustom = app.customOverride;
    boolean canSwitch = onCustom || app.customApi().ready();
    if (canSwitch) {
      String switchLabel = onCustom
          ? UiText.get(R.string.main_extra_81)
          : UiText.get(R.string.main_extra_69);
      TextView crossSwitch = ui.text(switchLabel, 15, ui.fg);
      crossSwitch.setPadding(ui.dp(14), 0, ui.dp(14), 0);
      crossSwitch.setMinHeight(ui.dp(48));
      crossSwitch.setGravity(Gravity.CENTER_VERTICAL);
      crossSwitch.setBackground(ui.rounded(ui.panel, 14));
      ui.clickable(crossSwitch, () -> {
        dismiss();
        MainActivity main = (MainActivity) activity;
        if (onCustom) main.confirmRestorePrimary();
        else main.switchToCustomModel();
      });
      LinearLayout.LayoutParams crossParams = new LinearLayout.LayoutParams(-1, ui.dp(48));
      crossParams.topMargin = ui.dp(4); crossParams.bottomMargin = ui.dp(6);
      panelView.addView(crossSwitch, crossParams);
    }
    TextView catalogStatus = ui.text("", 12, ui.muted);
    panelView.addView(catalogStatus);
    ScrollView modelScroll = new ModelListScrollView(activity);
    int modelListBackground = ui.bg == Color.BLACK ? 0xff161616 : 0xfff8f8f8;
    modelScroll.setContentDescription(UiText.get(R.string.network_text_003));
    modelScroll.setBackground(ui.rounded(modelListBackground, 16));
    modelScroll.setClipToOutline(true);
    modelScroll.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
    modelScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
    LinearLayout modelList = ui.column(); modelScroll.addView(modelList);
    int modelCount = customEntries != null && !customEntries.isEmpty() ? customEntries.size() : app.models.length();
    LinearLayout.LayoutParams modelScrollParams = new LinearLayout.LayoutParams(-1, ui.dp(Math.min(240, modelCount * 52 + 8)));
    modelScrollParams.topMargin = ui.dp(8);
    panelView.addView(modelScroll, modelScrollParams);
    LinearLayout effortHeader = ui.row();
    effortHeader.setPadding(ui.dp(4), ui.dp(20), ui.dp(4), ui.dp(4));
    TextView effortTitle = ui.text(UiText.get(R.string.network_text_004), 13, ui.muted);
    effortHeader.addView(effortTitle, new LinearLayout.LayoutParams(0, -2, 1));
    TextView resetEffort = ui.text(UiText.get(R.string.network_text_005), 12, ui.muted);
    resetEffort.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8));
    resetEffort.setContentDescription(UiText.get(R.string.network_text_006));
    effortHeader.addView(resetEffort);
    panelView.addView(effortHeader);
    LinearLayout effortPanel = ui.column();
    TextView effortValue = ui.text(UiText.get(R.string.network_text_005), 22, ui.fg);
    effortValue.setTypeface(null, Typeface.BOLD);
    effortValue.setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(4));
    effortPanel.addView(effortValue);
    SeekBar effortSlider = new SeekBar(activity);
    effortSlider.setContentDescription(UiText.get(R.string.network_text_007));
    effortSlider.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    effortSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.fg));
    effortSlider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.line));
    effortSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.fg));
    GradientDrawable tick = new GradientDrawable();
    tick.setShape(GradientDrawable.OVAL); tick.setColor(ui.muted); tick.setSize(ui.dp(3), ui.dp(3));
    effortSlider.setTickMark(tick);
    effortSlider.setKeyProgressIncrement(1);
    effortPanel.addView(effortSlider, new LinearLayout.LayoutParams(-1, ui.dp(48)));
    LinearLayout endpoints = ui.row(); endpoints.setPadding(ui.dp(6), 0, ui.dp(6), 0);
    TextView low = ui.text("", 12, ui.muted), high = ui.text("", 12, ui.muted);
    endpoints.addView(low, new LinearLayout.LayoutParams(0, -2, 1));
    high.setGravity(Gravity.END); endpoints.addView(high, new LinearLayout.LayoutParams(0, -2, 1));
    effortPanel.addView(endpoints);
    panelView.addView(effortPanel);
    TextView effortHint = ui.text("", 12, ui.muted);
    effortHint.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(16)); panelView.addView(effortHint);
    TextView speedTitle = ui.text(UiText.get(R.string.network_text_008), 13, ui.muted);
    speedTitle.setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(8)); panelView.addView(speedTitle);
    LinearLayout speedChoices = ui.row(); panelView.addView(speedChoices);
    TextView speedHint = ui.text("", 12, ui.muted);
    speedHint.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(12)); panelView.addView(speedHint);
    Dialog[] holder = new Dialog[1];
    Runnable[] redraw = new Runnable[1];
    redraw[0] = () -> {
      boolean customList = customEntries != null && !customEntries.isEmpty();
      catalogStatus.setText(customList
          ? activity.getString(R.string.main_extra_82, customEntries.size())
          : app.modelsStatus.isEmpty() ? UiText.get(R.string.network_text_009) : app.modelsStatus);
      modelScroll.getLayoutParams().height = ui.dp(Math.min(240, (customList ? customEntries.size() : app.models.length()) * 52 + 8));
      modelScroll.requestLayout();
      modelList.removeAllViews();
      if (customList) {
        // Selection is keyed by entry id: inherit the active entry, fall back to the first.
        boolean listed = false;
        for (CustomApiConfig.Entry e : customEntries) if (e.id.equals(selected[0])) { listed = true; break; }
        if (!listed) {
          String activeId = app.customApi().activeId;
          selected[0] = "";
          for (CustomApiConfig.Entry e : customEntries) if (e.id.equals(activeId)) { selected[0] = e.id; break; }
          if (selected[0].isEmpty()) selected[0] = customEntries.get(0).id;
        }
        for (CustomApiConfig.Entry e : customEntries) {
          boolean checked = selected[0].equals(e.id);
          LinearLayout option = ui.row(); option.setPadding(ui.dp(14), 0, ui.dp(10), 0);
          option.setBackground(ui.rounded(checked ? ui.panel : modelListBackground, 14));
          String label = e.name.isEmpty() ? e.model : e.name + " · " + e.model;
          TextView row = ui.text(label, 15, ui.fg);
          row.setMaxLines(1); row.setEllipsize(TextUtils.TruncateAt.END); row.setGravity(Gravity.CENTER_VERTICAL);
          option.addView(row, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
          if (checked) option.addView(new IconView(activity, "check", ui.fg), new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
          option.setContentDescription(UiText.get(R.string.network_text_011) + e.model);
          ui.clickable(option, () -> { selected[0] = e.id; selected[1] = ""; redraw[0].run(); });
          modelList.addView(option);
        }
      } else {
      // No "remote default" row anymore: inherit the last choice, and when
      // there is none (or the catalog no longer offers it) fall back to the
      // first listed model — the picker always resolves to a concrete model.
      if (app.models.length() > 0) {
        boolean listed = false;
        for (int m = 0; !listed && m < app.models.length(); m++) {
          JSONObject candidate = app.models.optJSONObject(m);
          if (candidate != null && ModelOptions.id(candidate).equals(selected[0])) listed = true;
        }
        if (!listed) {
          JSONObject first = app.models.optJSONObject(0);
          if (first != null) selected[0] = ModelOptions.id(first);
        }
      }
      for (int i = 0; i < app.models.length(); i++) {
        JSONObject model = app.models.optJSONObject(i);
        if (model == null) continue;
        String id = ModelOptions.id(model);
        boolean checked = selected[0].equals(id);
        LinearLayout option = ui.row(); option.setPadding(ui.dp(14), 0, ui.dp(10), 0);
        option.setBackground(ui.rounded(checked ? ui.panel : modelListBackground, 14));
        TextView label = ui.text(ModelOptions.displayName(app.models, id), 15, ui.fg);
        label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END); label.setGravity(Gravity.CENTER_VERTICAL);
        option.addView(label, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        if (checked) option.addView(new IconView(activity, "check", ui.fg), new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
        option.setContentDescription(UiText.get(R.string.network_text_011) + id);
        ui.clickable(option, () -> {
          selected[0] = id; selected[1] = ""; redraw[0].run();
          effortPanel.setAlpha(.3f); effortPanel.setTranslationY(ui.dp(6));
          effortPanel.animate().alpha(1).translationY(0).setDuration(180).start();
        });
        modelList.addView(option);
      }
      }
      JSONObject resolved = app.selectedModel(selected[0]);
      JSONObject chosen = resolved;
      speedChoices.removeAllViews();
      String supported = ServiceTierOptions.supportedId(resolved, selected[2]);
      boolean revoked = !selected[2].isEmpty() && (!app.supportsServiceTier()
          || !selected[2].equals(ServiceTierOptions.STANDARD) && (!app.speedSelectionEnabled() || supported.isEmpty()));
      if (revoked) selected[2] = "";
      else if (!selected[2].isEmpty()) selected[2] = supported;
      List<JSONObject> speeds = new ArrayList<>();
      speeds.add(Json.obj("id", "", "name", UiText.get(R.string.network_text_010)));
      if (app.supportsServiceTier()) speeds.add(Json.obj("id", ServiceTierOptions.STANDARD, "name", UiText.get(R.string.network_text_012)));
      if (app.speedSelectionEnabled()) speeds.addAll(ServiceTierOptions.tiers(resolved));
      // Each option stays readable when a future server offers more than Fast.
      LinearLayout speedRows = ui.column();
      speedChoices.addView(speedRows, new LinearLayout.LayoutParams(-1, -2));
      LinearLayout speedRow = null;
      for (int i = 0; i < speeds.size(); i++) {
        if (i % 3 == 0) { speedRow = ui.row(); speedRows.addView(speedRow); }
        String tier = speeds.get(i).optString("id");
        TextView option = ui.text(ServiceTierOptions.label(tier, resolved), 13, ui.fg);
        option.setGravity(Gravity.CENTER); option.setMinHeight(ui.dp(44)); option.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        option.setBackground(ui.rounded(selected[2].equals(tier) ? ui.panel : ui.bg, 12));
        option.setTypeface(null, selected[2].equals(tier) ? Typeface.BOLD : Typeface.NORMAL);
        option.setContentDescription(UiText.get(R.string.network_text_013) + (tier.isEmpty() ? UiText.get(R.string.network_text_010) : tier));
        option.setSelected(selected[2].equals(tier));
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(0, -2, 1);
        speedParams.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        speedRow.addView(option, speedParams);
        ui.clickable(option, () -> { selected[2] = tier; redraw[0].run(); });
      }
      String speedDetail = "";
      if (selected[2].isEmpty()) speedDetail = app.inheritedTierKnown || app.modelSettings.configKnown
          ? UiText.get(R.string.network_text_014) + ServiceTierOptions.label(app.defaultServiceTier(resolved), resolved) : UiText.get(R.string.network_text_015);
      else if (selected[2].equals(ServiceTierOptions.STANDARD)) speedDetail = UiText.get(R.string.network_text_016);
      else speedDetail = ServiceTierOptions.description(selected[2], resolved);
      if (!selected[2].isEmpty() && !selected[2].equals(ServiceTierOptions.STANDARD) && speedDetail.isEmpty())
        speedDetail = UiText.get(R.string.network_text_017) + selected[2] + UiText.get(R.string.network_text_018);
      if (!app.supportsServiceTier()) speedDetail = UiText.get(R.string.network_text_019);
      else if (!app.speedSelectionEnabled()) speedDetail += UiText.get(R.string.network_text_020);
      else if (resolved == null) speedDetail += app.modelSettingsLoading ? UiText.get(R.string.network_text_021) : UiText.get(R.string.network_text_022);
      else if (ServiceTierOptions.tiers(resolved).isEmpty()) speedDetail += UiText.get(R.string.network_text_023);
      if (revoked) speedDetail = UiText.get(R.string.network_text_024) + speedDetail;
      speedHint.setText(speedDetail);
      List<String> efforts = ModelOptions.efforts(chosen);
      effortPanel.setVisibility(efforts.isEmpty() ? View.GONE : View.VISIBLE);
      String defaultEffort = chosen == null ? "" : chosen.optString("defaultReasoningEffort");
      int defaultIndex = Math.max(0, efforts.indexOf(defaultEffort));
      int index = efforts.indexOf(selected[1]);
      if (index < 0) index = defaultIndex;
      effortSlider.setOnSeekBarChangeListener(null);
      effortSlider.setMax(Math.max(1, efforts.size() - 1));
      effortSlider.setEnabled(efforts.size() > 1);
      effortSlider.setProgress(efforts.isEmpty() ? 0 : index);
      if (!selected[1].isEmpty() && !efforts.contains(selected[1])) selected[1] = "";
      effortValue.setText(efforts.isEmpty() ? UiText.get(R.string.network_text_005) : ModelOptions.label(efforts.get(index)));
      if (Build.VERSION.SDK_INT >= 30) effortSlider.setStateDescription(effortValue.getText());
      low.setText(efforts.isEmpty() ? "" : ModelOptions.label(efforts.get(0)));
      high.setText(efforts.size() < 2 ? "" : ModelOptions.label(efforts.get(efforts.size() - 1)));
      effortHint.setText(efforts.isEmpty() ? UiText.get(R.string.network_text_025) : UiText.get(R.string.network_text_026) + ModelOptions.label(efforts.get(defaultIndex)));
      resetEffort.setEnabled(!efforts.isEmpty());
      resetEffort.setAlpha(efforts.isEmpty() ? .4f : 1);
      ui.clickable(resetEffort, () -> {
        effortSlider.setProgress(defaultIndex); selected[1] = "";
      });
      effortSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
          if (efforts.isEmpty()) return;
          selected[1] = efforts.get(Math.min(progress, efforts.size() - 1));
          effortValue.setText(ModelOptions.label(selected[1]));
          if (Build.VERSION.SDK_INT >= 30) bar.setStateDescription(effortValue.getText());
          if (fromUser) bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        public void onStartTrackingTouch(SeekBar bar) {
          effortValue.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
        }
        public void onStopTrackingTouch(SeekBar bar) {
          effortValue.animate().scaleX(1).scaleY(1).setDuration(120).start();
        }
      });
    };
    redraw[0].run();
    TextView apply = ui.action(UiText.get(R.string.network_text_027), () -> {
      if (!app.connected || app.profile != modelProfile || !app.threadId.equals(modelThread)) {
        dismiss(); return;
      }
      if (customEntries != null && !customEntries.isEmpty()) {
        // Custom channel: a different entry = backend provider switch (rewrite
        // config + restart + reconnect); same entry = nothing to change.
        for (CustomApiConfig.Entry e : customEntries) {
          if (!e.id.equals(selected[0])) continue;
          if (!e.id.equals(app.customApi().activeId)) ((MainActivity) activity).switchCustomApiEntry(e);
          break;
        }
        dismiss(); return;
      }
      app.model = selected[0]; app.reasoningEffort = selected[1];
      app.rememberLastSessionChoices();
      app.chooseServiceTier(selected[2]); dismiss();
    });
    apply.setTextColor(ui.bg); apply.setBackground(ui.rounded(ui.fg, 22));
    ScrollView pickerScroll = new ScrollView(activity); pickerScroll.setFillViewport(false); pickerScroll.addView(panelView);
    LinearLayout pickerContainer = ui.column();
    int available = Math.max(ui.dp(180), activity.getResources().getDisplayMetrics().heightPixels - ui.dp(232));
    panelView.measure(View.MeasureSpec.makeMeasureSpec(wide ? ui.dp(500) : activity.getResources().getDisplayMetrics().widthPixels - ui.dp(16), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    pickerContainer.addView(pickerScroll, new LinearLayout.LayoutParams(-1, Math.min(available, panelView.getMeasuredHeight())));
    LinearLayout pickerFooter = ui.column(); pickerFooter.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(8));
    pickerFooter.addView(apply); pickerContainer.addView(pickerFooter);
    holder[0] = ui.sheet(UiText.get(R.string.network_text_028), pickerContainer, wide);
    dialog = holder[0];
    final JSONArray[] rendered = {app.models};
    final ModelSettings[] renderedSettings = {app.modelSettings};
    final boolean[] settingsLoading = {app.modelSettingsLoading};
    Runnable updateModels = () -> {
      if (!dialog.isShowing()) return;
      if (!app.connected || app.profile != modelProfile || !app.threadId.equals(modelThread)) {
        dismiss(); return;
      }
      catalogStatus.setText(app.modelsStatus);
      if (rendered[0] != app.models || renderedSettings[0] != app.modelSettings || settingsLoading[0] != app.modelSettingsLoading) {
        rendered[0] = app.models;
        renderedSettings[0] = app.modelSettings; settingsLoading[0] = app.modelSettingsLoading;
        redraw[0].run();
      }
    };
    catalogListener = updateModels;
    app.stateListeners.add(updateModels);
    holder[0].setOnDismissListener(d -> releaseListener());
    app.refreshModels();
    app.refreshModelSettings();
  }

}
