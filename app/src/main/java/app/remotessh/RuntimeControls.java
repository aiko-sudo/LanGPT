package app.remotessh;

import android.view.View;
import android.widget.TextView;

/** UI projection of negotiated capabilities, without a second capability state. */
final class RuntimeControls {
  static void apply(TextView model, View permission, RuntimeCapabilities capabilities) {
    model.setEnabled(capabilities.supports("models"));
    if (!model.isEnabled()) model.setText("运行时默认 · 模型目录未开放");
    model.setTooltipText(model.isEnabled() ? model.getText() : capabilities.reason());
    permission.setEnabled(capabilities.supports("approval"));
    if (!permission.isEnabled()) permission.setContentDescription(capabilities.reason());
  }
}
