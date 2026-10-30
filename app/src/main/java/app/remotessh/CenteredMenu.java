package app.remotessh;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Shared compact menu: title-free, centered rows, 60% screen width, optional version header. */
final class CenteredMenu {
  static AlertDialog show(Activity activity,int foreground,String[] labels,IntConsumer action) {
    return show(activity,foreground,Color.GRAY,null,labels,action);
  }

  static AlertDialog show(Activity activity,int foreground,int muted,String version,CharSequence[] labels,IntConsumer action) {
    return show(activity,foreground,muted,version,labels,action,null);
  }

  /** Same menu, but hands the header view back so live data can repaint it. */
  static AlertDialog show(Activity activity,int foreground,int muted,String version,CharSequence[] labels,
      IntConsumer action,java.util.function.Consumer<TextView> headerSink) {
    float density=activity.getResources().getDisplayMetrics().density;
    LinearLayout content=new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    if(version!=null && !version.isEmpty()) {
      TextView header=new TextView(activity);
      header.setText(version);
      header.setTextSize(11);
      header.setTextColor(muted);
      header.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
      header.setPadding(Math.round(20*density),Math.round(10*density),Math.round(20*density),Math.round(4*density));
      content.addView(header);
      // 头部与首个操作行之间也要一条两端渐隐分隔线（用量等头部可滚动的菜单）。
      content.addView(divider(activity,muted,density));
      if(headerSink!=null)headerSink.accept(header);
    }
    final AlertDialog[] holder=new AlertDialog[1];
    for(int i=0;i<labels.length;i++) {
      if(i>0)content.addView(divider(activity,muted,density));
      TextView label=new TextView(activity);
      label.setText(labels[i]);
      label.setTextSize(15);
      label.setTextColor(foreground);
      label.setGravity(Gravity.CENTER);
      int padding=Math.round(12*density);
      label.setPadding(padding,padding,padding,padding);
      label.setMinHeight(Math.round(48*density));
      final int which=i;
      label.setOnClickListener(view -> {
        AlertDialog dialog=holder[0];
        if(dialog!=null)dialog.dismiss();
        action.accept(which);
      });
      content.addView(label,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    ScrollView scroll=new ScrollView(activity);
    scroll.addView(content,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    AlertDialog menu=new AlertDialog.Builder(activity).setView(scroll).create();
    holder[0]=menu;
    menu.show();
    if(menu.getWindow()!=null)menu.getWindow().setLayout(
        Math.round(activity.getResources().getDisplayMetrics().widthPixels*.60f),ViewGroup.LayoutParams.WRAP_CONTENT);
    return menu;
  }

  /** Dark hairline whose two ends fade out over ~30% of its width each. */
  static View divider(Activity activity,int muted,float density) {
    View line=new View(activity);
    int base=muted==0?0xff8e8e93:muted;
    int[] stops=new int[11];
    for(int i=0;i<=10;i++) {
      float alpha;
      if(i<=3)alpha=i/3f;
      else if(i>=7)alpha=(10-i)/3f;
      else alpha=1;
      stops[i]=(base&0x00ffffff)|((int)(0x55*alpha)<<24);
    }
    line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,stops));
    line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.max(1,Math.round(density))));
    return line;
  }
}
