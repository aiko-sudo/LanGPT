package app.remotessh;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/** Follow only while the reader is at the latest message, including delayed layout. */
public final class ConversationScrollView extends ScrollView {
  private boolean following = true, layingOut, pinning;
  private View horizontalGesture;
  // Home-screen pull-down shortcut (armed only on the home surface, so chat
  // scrolling is never intercepted). Reuses the direction-locked gesture the
  // project page uses for pull-to-refresh.
  private PullRefreshGesture pullGesture;
  private Runnable pullAction;
  private java.util.function.Consumer<String> pullHint;
  private boolean pullArmed;
  public ConversationScrollView(Context context) { super(context); }
  void setPullAction(Runnable action, java.util.function.Consumer<String> hint) {
    this.pullAction = action; this.pullHint = hint;
    this.pullGesture = new PullRefreshGesture(
        android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop(),
        72 * getContext().getResources().getDisplayMetrics().density);
  }
  void armPull(boolean armed) {
    pullArmed = armed && pullGesture != null;
    if (!armed && pullHint != null && pullGesture != null) { pullGesture.cancel(); pullHint.accept(""); }
  }
  @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
    int action = event.getActionMasked();
    if (pullArmed && action == android.view.MotionEvent.ACTION_DOWN) {
      pullGesture.begin(event.getX(), event.getY(), getScrollY() == 0, false);
    } else if (pullArmed && action == android.view.MotionEvent.ACTION_MOVE
        && pullGesture.move(event.getX(), event.getY(), getScrollY() == 0)) {
      return true;
    }
    return super.onInterceptTouchEvent(event);
  }
  @Override public boolean onTouchEvent(android.view.MotionEvent event) {
    int action = event.getActionMasked();
    if (pullArmed && action == android.view.MotionEvent.ACTION_MOVE
        && pullGesture.move(event.getX(), event.getY(), getScrollY() == 0)) {
      pullHint.accept(pullGesture.ready()
          ? UiText.get(R.string.main_text_221) : UiText.get(R.string.main_text_220));
      return true;
    }
    if (pullArmed && action == android.view.MotionEvent.ACTION_UP && pullGesture.pulling()) {
      boolean ready = pullGesture.release();
      if (ready) { pullHint.accept(""); pullAction.run(); }
      else pullHint.accept("");
      return true;
    }
    if (pullArmed && action == android.view.MotionEvent.ACTION_CANCEL) {
      pullGesture.cancel(); pullHint.accept("");
    }
    return super.onTouchEvent(event);
  }
  public boolean isFollowing() { return following; }
  public void follow() { following = true; requestLayout(); }
  public void read() { following = false; }
  void holdHorizontalGesture(View owner) { horizontalGesture = owner; }
  void releaseHorizontalGesture(View owner) {
    if (horizontalGesture == owner) horizontalGesture = null;
  }
  @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
    // Selectable table text may request focus on UP. Preserve the reading Y for
    // that horizontal gesture while still letting Android update focus normally.
    return horizontalGesture != null ? 0 : super.computeScrollDeltaToGetChildRectOnScreen(rect);
  }
  public boolean nearBottom() {
    return getChildCount() == 0 || getChildAt(0).getHeight() + getPaddingTop()
        + getPaddingBottom() - getHeight() - getScrollY() <= 2 * getResources().getDisplayMetrics().density;
  }
  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int readingY = getScrollY();
    layingOut = true;
    super.onLayout(changed, l, t, r, b);
    if (!following) scrollTo(0, readingY);
    layingOut = false;
    pin();
  }
  public void pin() {
    if (!following || getChildCount() == 0) return;
    pinning = true;
    scrollTo(0, Math.max(0, getChildAt(0).getHeight() - getHeight() + getPaddingTop() + getPaddingBottom()));
    pinning = false;
  }
  @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
    if (!layingOut && !pinning) following = nearBottom();
    super.onScrollChanged(x, y, oldX, oldY);
  }
}
