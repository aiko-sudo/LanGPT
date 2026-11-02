package app.remotessh;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Owns the connection draft, credential form and key-import result for one window. */
final class ProfileEditor {
  static final int REQUEST_KEY = 42;
  interface Actions {
    void connect(Profile profile);
    void removed();
  }

  private final Activity activity;
  private final RemoteApp app;
  private final DialogUi ui;
  private final boolean wide;
  private final Actions actions;
  private Dialog dialog;
  private EditText keyEditor;

  ProfileEditor(Activity activity, RemoteApp app, DialogUi ui, boolean wide, Actions actions) {
    this.activity = activity; this.app = app; this.ui = ui; this.wide = wide; this.actions = actions;
  }

  boolean isShowing() { return dialog != null && dialog.isShowing(); }
  void dismiss() { if (dialog != null) dialog.dismiss(); }

  void importKey(Uri uri) {
    if (!isShowing() || keyEditor == null || uri == null) return;
    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException(UiText.get(R.string.main_text_71));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (bytes.size() + count > 65536) throw new IOException(UiText.get(R.string.main_text_72));
        bytes.write(buffer, 0, count);
      }
      keyEditor.setText(bytes.toString(StandardCharsets.UTF_8.name()));
    } catch (Exception error) { ui.toast(error.getMessage()); }
  }

  void show(Profile original, boolean fresh) {
    if (isShowing()) return;
    Profile draft = Profile.from(original.json());
    draft.password = original.password;
    draft.privateKey = original.privateKey;
    draft.passphrase = original.passphrase;
    LinearLayout form = ui.column();
    form.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16));
    ScrollView sc = new ScrollView(activity);
    sc.addView(form);
    TextView runtimeLabel = ui.text(UiText.get(R.string.main_text_31), 13, ui.muted);
    runtimeLabel.setPadding(0, ui.dp(10), 0, ui.dp(4));
    form.addView(runtimeLabel);
    RadioGroup runtimes = new RadioGroup(activity);
    runtimes.setOrientation(LinearLayout.VERTICAL);
    String[] runtimeKeys = {"codex", "opencode", "dsh", "claude"};
    String[] runtimeNames = {"Codex", "OpenCode", "DSH", "CC"};
    java.util.Map<String, String> runtimeCommand = new java.util.HashMap<>();
    runtimeCommand.put("codex", "codex app-server");
    runtimeCommand.put("opencode", "opencode serve --port 4610 --hostname 127.0.0.1");
    runtimeCommand.put("dsh", "npx -y @deepseek-ai/dsh@0.2.0-rc.2");
    runtimeCommand.put("claude", "claude");
    String initialRuntime = draft.runtime == null || draft.runtime.isEmpty() ? "codex" : draft.runtime;
    java.util.List<RadioButton> runtimeButtons = new java.util.ArrayList<>();
    for (int i = 0; i < runtimeKeys.length; i++) {
      RadioButton button = new RadioButton(activity);
      AppIcons.selection(button, ui.fg, ui.muted, true);
      button.setText(runtimeNames[i]);
      button.setTextColor(ui.fg);
      button.setTag(runtimeKeys[i]);
      runtimes.addView(button, new RadioGroup.LayoutParams(-1, -2));
      runtimeButtons.add(button);
      if (runtimeKeys[i].equals(initialRuntime)) button.setChecked(true);
    }
    form.addView(runtimes);
    TextView runtimeHelp = ui.text(UiText.get(R.string.main_text_73), 13, ui.muted);
    form.addView(runtimeHelp);
    EditText name = ui.field(form, UiText.get(R.string.main_text_32), draft.name, UiText.get(R.string.main_text_33), false),
        host = ui.field(form, UiText.get(R.string.main_text_34), draft.host, "192.168.1.10", false),
        port = ui.field(form, UiText.get(R.string.main_text_35), String.valueOf(draft.port), "22", false);
    port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    EditText fallbackHost = ui.field(form, UiText.get(R.string.main_text_36), draft.fallbackHost, UiText.get(R.string.main_extra_1), false),
        fallbackPort = ui.field(form, UiText.get(R.string.main_text_37), draft.fallbackPort > 0 ? String.valueOf(draft.fallbackPort) : "", "22", false);
    fallbackPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    TextView routeHelp = ui.text(UiText.get(R.string.main_text_74), 13, ui.muted);
    form.addView(routeHelp);
    EditText user = ui.field(form, UiText.get(R.string.main_text_38), draft.user, UiText.get(R.string.main_text_38), false);
    RadioGroup auth = new RadioGroup(activity);
    auth.setOrientation(LinearLayout.VERTICAL);
    auth.setPadding(0, ui.dp(12), 0, ui.dp(4));
    RadioButton passwordAuth = new RadioButton(activity);
    AppIcons.selection(passwordAuth,ui.fg,ui.muted,true);
    passwordAuth.setId(R.id.auth_password);
    passwordAuth.setText(UiText.get(R.string.main_text_39));
    passwordAuth.setTextColor(ui.fg);
    RadioButton keyAuth = new RadioButton(activity);
    AppIcons.selection(keyAuth,ui.fg,ui.muted,true);
    keyAuth.setId(R.id.auth_key);
    keyAuth.setText(UiText.get(R.string.main_text_40));
    keyAuth.setTextColor(ui.fg);
    auth.addView(passwordAuth);
    auth.addView(keyAuth);
    RadioButton tailscaleAuth = new RadioButton(activity);
    AppIcons.selection(tailscaleAuth,ui.fg,ui.muted,true);
    tailscaleAuth.setId(R.id.auth_tailscale);
    tailscaleAuth.setText(UiText.get(R.string.main_text_41));
    tailscaleAuth.setTextColor(ui.fg);
    auth.addView(tailscaleAuth);
    form.addView(auth);
    TextView networkHelp = ui.text(UiText.get(R.string.main_text_75), 13, ui.muted);
    form.addView(networkHelp);
    LinearLayout passwordSection = ui.column();
    form.addView(passwordSection);
    EditText password = ui.field(passwordSection, UiText.get(R.string.main_text_42), draft.password, UiText.get(R.string.main_text_43), true);
    LinearLayout keySection = ui.column();
    form.addView(keySection);
    EditText key = ui.field(keySection, UiText.get(R.string.main_text_44), draft.privateKey, UiText.get(R.string.main_text_45), true);
    key.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setMinLines(3);
    key.setMaxLines(5);
    keyEditor = key;
    keySection.addView(
        ui.action(
            UiText.get(R.string.main_text_46),
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              activity.startActivityForResult(i, REQUEST_KEY);
            }));
    EditText passphrase = ui.field(keySection, UiText.get(R.string.main_text_47), draft.passphrase, UiText.get(R.string.main_text_48), true);
    auth.setOnCheckedChangeListener(
        (group, checked) -> {
          passwordSection.setVisibility(checked == R.id.auth_password ? View.VISIBLE : View.GONE);
          keySection.setVisibility(checked == R.id.auth_key ? View.VISIBLE : View.GONE);
        });
    auth.check(draft.tailscaleSsh ? R.id.auth_tailscale : draft.privateKey.isEmpty() ? R.id.auth_password : R.id.auth_key);
    TextView advanced = ui.action(UiText.get(R.string.main_text_49), () -> {});
    advanced.setText(AppIcons.label(activity, advanced.getText(), null, "chevronDown", ui.fg, ui.dp(18)));
    LinearLayout.LayoutParams advancedLayout = new LinearLayout.LayoutParams(-1, -2);
    advancedLayout.topMargin = ui.dp(12);
    form.addView(advanced, advancedLayout);
    LinearLayout extra = ui.column();
    extra.setVisibility(draft.cwd.isEmpty() ? View.GONE : View.VISIBLE);
    form.addView(extra);
    ui.clickable(
        advanced,
        () ->
            extra.setVisibility(extra.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    EditText cwd = ui.field(extra, UiText.get(R.string.main_text_50), draft.cwd, UiText.get(R.string.main_extra_2), false),
        command = ui.field(extra, UiText.get(R.string.main_text_51), draft.command, "codex app-server", false);
    CheckBox persistent = new CheckBox(activity);
    AppIcons.selection(persistent,ui.fg,ui.muted,false);
    persistent.setText(UiText.get(R.string.main_text_52));
    persistent.setTextColor(ui.fg);
    persistent.setChecked(draft.persistent);
    extra.addView(persistent);
    TextView note = ui.text(UiText.get(R.string.main_text_76), 12, ui.muted);
    note.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
    extra.addView(note);
    CheckBox remember = new CheckBox(activity);
    AppIcons.selection(remember,ui.fg,ui.muted,false);
    remember.setText(UiText.get(R.string.main_text_53));
    remember.setTextColor(ui.fg);
    remember.setTextSize(13);
    remember.setChecked(draft.remember);
    form.addView(remember);
    TextView validation = ui.text("", 13, Color.rgb(181, 63, 49));
    LinearLayout footer = ui.column();
    footer.addView(validation);
    Dialog dialog = ui.profileWindow(fresh ? UiText.get(R.string.main_text_54) : UiText.get(R.string.main_text_55), sc, footer, wide);
    this.dialog = dialog;
    dialog.setOnDismissListener(d -> keyEditor = null);
    java.util.function.Consumer<Boolean> persist =
        shouldConnect -> {
          try {
            for (RadioButton button : runtimeButtons)
              if (button.isChecked()) draft.runtime = String.valueOf(button.getTag());
            draft.name = name.getText().toString().trim();
            draft.host = host.getText().toString().trim();
            draft.port = Integer.parseInt(port.getText().toString());
            draft.fallbackHost = fallbackHost.getText().toString().trim();
            String fallbackPortText = fallbackPort.getText().toString().trim();
            draft.fallbackPort = fallbackPortText.isEmpty() ? 0 : Integer.parseInt(fallbackPortText);
            draft.user = user.getText().toString().trim();
            boolean useKey = auth.getCheckedRadioButtonId() == R.id.auth_key;
            draft.tailscaleSsh = auth.getCheckedRadioButtonId() == R.id.auth_tailscale;
            draft.password = useKey || draft.tailscaleSsh ? "" : password.getText().toString();
            draft.privateKey = useKey ? key.getText().toString() : "";
            draft.passphrase = useKey ? passphrase.getText().toString() : "";
            draft.cwd = cwd.getText().toString().trim();
            draft.command = command.getText().toString().trim();
            String preset = runtimeCommand.get(draft.runtime);
            if (preset != null && command.getText().toString().trim().isEmpty())
              draft.command = preset;
            draft.persistent = persistent.isChecked();
            draft.remember = remember.isChecked();
            draft.validate();
            if (shouldConnect && !draft.tailscaleSsh
                && (useKey ? draft.privateKey.trim().isEmpty() : draft.password.isEmpty()))
              throw new IllegalArgumentException(useKey ? UiText.get(R.string.main_text_56) : UiText.get(R.string.main_text_57));
            if (!app.storeProfile(draft)) {
              validation.setText(app.error);
              return;
            }
            dialog.dismiss();
            if (shouldConnect) actions.connect(draft);
            else {
              app.changed();
              ui.toast(UiText.get(R.string.main_text_58));
            }
          } catch (Exception ex) {
            validation.setText(ex instanceof NumberFormatException ? UiText.get(R.string.main_text_59) : ex.getMessage());
          }
        };
    LinearLayout buttons = ui.row();
    footer.addView(buttons);
    TextView save = ui.action(UiText.get(R.string.main_text_3), () -> persist.accept(false));
    buttons.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
    TextView connect = ui.action(UiText.get(R.string.main_text_60), () -> persist.accept(true));
    connect.setTextColor(ui.bg);
    connect.setBackground(ui.rounded(ui.fg, 24));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
    cp.leftMargin = ui.dp(12);
    buttons.addView(connect, cp);
    if (!fresh) {
      form.addView(
          ui.action(
              UiText.get(R.string.main_text_61),
              () -> {
                String target = original.host + ":" + original.port;
                String pin = app.vault.getPin(target);
                new AlertDialog.Builder(activity)
                    .setTitle(UiText.get(R.string.main_text_62))
                    .setMessage(target + "\n\n" + (pin == null ? UiText.get(R.string.main_text_63) : pin))
                    .setPositiveButton(UiText.get(R.string.main_text_2), null)
                    .setNeutralButton(
                        pin == null ? null : UiText.get(R.string.main_text_64),
                        (d, w) ->
                            new AlertDialog.Builder(activity)
                                .setTitle(UiText.get(R.string.main_text_65))
                                .setMessage(UiText.get(R.string.main_text_66))
                                .setNegativeButton(UiText.get(R.string.main_text_1), null)
                                .setPositiveButton(
                                    UiText.get(R.string.main_text_6),
                                    (confirm, which) -> {
                                      if (app.profile != null
                                          && (app.profile.host + ":" + app.profile.port)
                                              .equals(target)) app.disconnect();
                                      synchronized (app.vault) {
                                        app.vault.pins.remove(target);
                                        app.save();
                                      }
                                      ui.toast(UiText.get(R.string.main_text_67));
                                    })
                                .show())
                    .show();
              }));
      TextView removeBar = ui.action(
              UiText.get(R.string.main_text_68),
              () ->
                  new AlertDialog.Builder(activity)
                      .setTitle(UiText.get(R.string.main_text_69))
                      .setMessage(UiText.get(R.string.main_text_70))
                      .setNegativeButton(UiText.get(R.string.main_text_1), null)
                      .setPositiveButton(
                          UiText.get(R.string.main_text_5),
                          (d, w) -> {
                            if (app.profile != null && app.profile.id.equals(original.id))
                              app.disconnect();
                            app.vault.profiles.remove(original);
                            app.save();
                            dialog.dismiss();
                            actions.removed();
                          })
                      .show());
      // v1.0.4: spacing between adjacent full-width bars (fingerprint bar above touches this one)
      LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(-1, -2);
      removeLp.topMargin = ui.dp(10);
      form.addView(removeBar, removeLp);
    }
  }

}
