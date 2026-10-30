package app.remotessh;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;
import java.util.function.Consumer;

/** Pull down only at the top; horizontal row gestures remain child-owned. */
final class PullRefreshScrollView extends ScrollView {
  private final PullRefreshGesture gesture;
  private final Runnable refresh;
  private final Consumer<String> hint;
  private boolean refreshing;

  PullRefreshScrollView(Context context, Runnable refresh, Consumer<String> hint) {
    super(context);
    this.refresh=refresh; this.hint=hint;
    gesture=new PullRefreshGesture(ViewConfiguration.get(context).getScaledTouchSlop(),
        72*context.getResources().getDisplayMetrics().density);
    setFillViewport(true);
  }

  @Override public boolean onInterceptTouchEvent(MotionEvent event) {
    int action=event.getActionMasked();
    if (action==MotionEvent.ACTION_DOWN) {
      gesture.begin(event.getX(), event.getY(), getScrollY()==0, refreshing);
    } else if (action==MotionEvent.ACTION_MOVE && !refreshing) {
      if (gesture.move(event.getX(),event.getY(),getScrollY()==0)) return true;
    }
    return super.onInterceptTouchEvent(event);
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    int action=event.getActionMasked();
    if (action==MotionEvent.ACTION_MOVE && !refreshing
        && gesture.move(event.getX(),event.getY(),getScrollY()==0)) {
      hint.accept(gesture.ready()?"松开刷新":"下拉刷新");
      return true;
    }
    if (action==MotionEvent.ACTION_UP && gesture.pulling()) {
      boolean ready=gesture.release();
      if (ready) {
        refreshing=true; hint.accept("正在刷新…"); refresh.run();
      } else hint.accept("");
      return true;
    }
    if (action==MotionEvent.ACTION_CANCEL) {
      gesture.cancel(); if (!refreshing) hint.accept("");
    }
    return super.onTouchEvent(event);
  }

  void finishRefresh() { refreshing=false; gesture.cancel(); hint.accept(""); }
}
