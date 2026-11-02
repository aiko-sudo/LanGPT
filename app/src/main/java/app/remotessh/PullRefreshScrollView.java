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
  // 工作面板 Tab 快滑：挂在 dispatchTouchEvent 层，保证事件落在任意子 View
  // （卡片、文本、按钮）时手势检测器仍能收到完整事件流（onTouchListener 在
  // 子 View 消费 DOWN 后收不到后续事件，导致卡片区域滑不动）。
  private android.view.GestureDetector panelFling;

  void setPanelFlingDetector(android.view.GestureDetector detector) { this.panelFling = detector; }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    if (panelFling != null) panelFling.onTouchEvent(event);
    return super.dispatchTouchEvent(event);
  }

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
      hint.accept(gesture.ready()?UiText.get(R.string.work_text_217):UiText.get(R.string.work_text_218));
      return true;
    }
    if (action==MotionEvent.ACTION_UP && gesture.pulling()) {
      boolean ready=gesture.release();
      if (ready) {
        refreshing=true; hint.accept(UiText.get(R.string.work_text_219)); refresh.run();
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
