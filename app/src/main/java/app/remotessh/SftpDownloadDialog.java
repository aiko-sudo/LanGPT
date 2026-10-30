package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FilterOutputStream;
import java.io.OutputStream;

/**
 * SFTP 文件下载进度弹窗：目标位置由系统“选择保存位置”对话框（SAF）决定，
 * 选定后弹窗显示进度与速度，完成后自动消失。取消/失败时清理半截文件。
 */
final class SftpDownloadDialog {
  private static final long FALLBACK_LIMIT = 500L * 1024 * 1024;
  private static final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /** 发起系统“选择保存位置”对话框；结果在 MainActivity.onActivityResult(46) 处理。 */
  static void pickLocation(Activity activity, String name) {
    String fileName = name == null || name.isEmpty() ? "download" : name;
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    String mime = java.net.URLConnection.guessContentTypeFromName(fileName);
    intent.setType(mime == null ? "application/octet-stream" : mime);
    intent.putExtra(Intent.EXTRA_TITLE, fileName);
    try { activity.startActivityForResult(intent, 46); }
    catch (Exception e) { Toast.makeText(activity, "无法打开保存位置选择", Toast.LENGTH_SHORT).show(); }
  }

  static void start(Activity activity, RemoteApp app, String path, String name, long size, Uri target) {
    String fileName = name == null || name.isEmpty() ? "download" : name;
    Handler main = new Handler(Looper.getMainLooper());

    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
    box.setPadding(pad, pad / 2, pad, 0);
    TextView title = new TextView(activity);
    title.setText(fileName);
    TextView stats = new TextView(activity);
    stats.setText("准备下载…");
    ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    if (size > 0) { bar.setMax((int) Math.min(size, Integer.MAX_VALUE)); bar.setProgress(0); }
    else bar.setIndeterminate(true);
    box.addView(title); box.addView(bar); box.addView(stats);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    bar.setLayoutParams(lp); stats.setLayoutParams(lp);

    final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setView(box)
        .setNegativeButton("取消", (d, w) -> cancelled.set(true))
        .setCancelable(false)
        .show();

    // 速度：1 秒滑动窗口采样。
    final long[] windowBytes = {0}; final long[] windowAt = {0};
    Runnable[] ticker = new Runnable[1];
    ticker[0] = new Runnable() {
      @Override public void run() {
        long now = System.currentTimeMillis();
        long total = bytes.get();
        if (windowAt[0] == 0) { windowAt[0] = now; windowBytes[0] = total; }
        else if (now - windowAt[0] >= 1000) {
          long deltaB = total - windowBytes[0];
          double speed = deltaB * 1000.0 / (now - windowAt[0]);
          windowAt[0] = now; windowBytes[0] = total;
          String text = human(total) + " · " + human((long) speed) + "/s";
          if (size > 0) text += " · " + (total * 100 / Math.max(1, size)) + "%";
          stats.setText(text);
          if (size > 0) bar.setProgress((int) Math.min(total, Integer.MAX_VALUE));
        }
        main.postDelayed(this, 500);
      }
    };
    main.post(ticker[0]);

    worker.execute(() -> {
      java.io.File staged = null, backup = null;
      boolean writing = false;
      try {
        staged = java.io.File.createTempFile("sftp-", ".part", activity.getCacheDir());
        try (OutputStream raw = new java.io.FileOutputStream(staged)) {
          OutputStream counting = new FilterOutputStream(raw) {
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
              out.write(b, off, len); bytes.addAndGet(len);
            }
            @Override public void write(int b) throws java.io.IOException { out.write(b); bytes.incrementAndGet(); }
          };
          app.readMedia(path, counting, size > 0 ? size : FALLBACK_LIMIT, () -> !cancelled.get());
          counting.flush();
        }
        if (cancelled.get()) throw new java.io.IOException("下载已取消");
        backup = java.io.File.createTempFile("sftp-backup-", ".part", activity.getCacheDir());
        try (java.io.InputStream original = activity.getContentResolver().openInputStream(target);
             OutputStream saved = new java.io.FileOutputStream(backup)) {
          if (original == null) throw new java.io.IOException("无法备份目标文件，已停止以保护原文件");
          byte[] block = new byte[16384]; int n;
          while ((n = original.read(block)) != -1) saved.write(block, 0, n);
        }
        if (cancelled.get()) throw new java.io.IOException("下载已取消");
        writing = true;
        try (OutputStream destination = activity.getContentResolver().openOutputStream(target, "wt");
             java.io.InputStream source = new java.io.FileInputStream(staged)) {
          if (destination == null) throw new java.io.IOException("无法写入所选位置");
          byte[] block = new byte[16384]; int n;
          while ((n = source.read(block)) != -1) { if (cancelled.get()) throw new java.io.IOException("下载已取消"); destination.write(block, 0, n); }
        }
        main.post(() -> {
          main.removeCallbacks(ticker[0]);
          dialog.dismiss();
          Toast.makeText(activity, "已保存：" + fileName, Toast.LENGTH_LONG).show();
        });
      } catch (Exception e) {
        String detail = e.getMessage();
        if (writing && backup != null) {
          try (OutputStream destination = activity.getContentResolver().openOutputStream(target, "wt");
               java.io.InputStream source = new java.io.FileInputStream(backup)) {
            if (destination == null) throw new java.io.IOException("无法恢复");
            byte[] block = new byte[16384]; int n; while ((n = source.read(block)) != -1) destination.write(block, 0, n);
          } catch (Exception restore) { detail = "写入失败且自动恢复失败，请检查目标文件；备份保留于应用缓存"; backup = null; }
        }
        final String message = detail;
        main.post(() -> {
          main.removeCallbacks(ticker[0]);
          dialog.dismiss();
          Toast.makeText(activity, cancelled.get() ? "已取消下载" : "下载失败：" + message, Toast.LENGTH_LONG).show();
        });
      } finally { if (staged != null) staged.delete(); if (backup != null) backup.delete(); }
    });
  }

  private static String human(long v) {
    if (v >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1fG", v / 1073741824.0);
    if (v >= 1024L * 1024) return String.format(java.util.Locale.US, "%.1fM", v / 1048576.0);
    if (v >= 1024) return String.format(java.util.Locale.US, "%.0fK", v / 1024.0);
    return v + "B";
  }
}
