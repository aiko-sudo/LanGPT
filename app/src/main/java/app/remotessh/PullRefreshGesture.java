package app.remotessh;

/** Direction-locked pull gesture, independent of Android for regression tests. */
final class PullRefreshGesture {
  private final float slop, threshold;
  private float x, y;
  private boolean candidate, pulling;
  private float distance;

  PullRefreshGesture(float slop, float threshold) {
    this.slop = slop; this.threshold = threshold;
  }
  void begin(float x, float y, boolean atTop, boolean refreshing) {
    this.x=x; this.y=y; candidate=atTop && !refreshing; pulling=false; distance=0;
  }
  boolean move(float x, float y, boolean atTop) {
    if (!candidate) return false;
    float dx=x-this.x, dy=y-this.y;
    if (!pulling) {
      if (!atTop || dy < -slop || (Math.abs(dx)>slop && Math.abs(dx)>=Math.abs(dy))) {
        candidate=false; return false;
      }
      if (dy>slop && dy>Math.abs(dx)*1.25f) pulling=true;
    }
    if (pulling) distance=Math.max(0,dy);
    return pulling;
  }
  boolean ready() { return pulling && distance>=threshold; }
  boolean pulling() { return pulling; }
  boolean release() { boolean refresh=ready(); cancel(); return refresh; }
  void cancel() { candidate=false; pulling=false; distance=0; }
}
