package app.remotessh;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** One-action swipe row: drag content left to reveal the destructive action. */
final class SwipeActionLayout extends FrameLayout {
  interface Action { void run(); }

  private final View content;
  private final int actionWidth, touchSlop;
  private float downX, downY, startX;
  private boolean dragging;

  SwipeActionLayout(Context context, View content, int actionWidth, String label, int bgColor, Action action) {
    super(context);
    this.content = content;
    this.actionWidth = actionWidth;
    this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    setClipChildren(false);
    setClipToPadding(false);
    // The content row must be opaque: without a background the red action
    // would show through and appear permanently visible.
    content.setBackgroundColor(bgColor);

    TextView button = new TextView(context);
    button.setText(label);
    button.setTextSize(15);
    button.setTextColor(Color.WHITE);
    button.setGravity(android.view.Gravity.CENTER);
    button.setContentDescription(label + "此历史会话");
    button.setBackground(new ColorDrawable(0xffd9342b));
    button.setOnClickListener(v -> action.run());
    LayoutParams actionParams = new LayoutParams(actionWidth, -1, android.view.Gravity.RIGHT);
    addView(button, actionParams);
    addView(content, new LayoutParams(-1, -1));
  }

  private void settle(boolean open) {
    content.animate().translationX(open ? -actionWidth : 0).setDuration(160).start();
  }

  @Override public boolean onInterceptTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = event.getX(); downY = event.getY(); startX = content.getTranslationX(); dragging = false;
        return false;
      case MotionEvent.ACTION_MOVE:
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
          dragging = true;
          // Claim the gesture so the outer ScrollView stops stealing it.
          ViewParent parent = getParent();
          if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
          return true;
        }
        return false;
      default: return false;
    }
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_MOVE:
        if (!dragging) return true;
        float x = Math.max(-actionWidth, Math.min(0, startX + event.getX() - downX));
        content.setTranslationX(x);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (dragging) settle(content.getTranslationX() < -actionWidth / 2f);
        dragging = false;
        return true;
      default: return true;
    }
  }
}
