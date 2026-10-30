package app.remotessh;

import android.app.*;
import android.widget.*;

/** Queue manager owns its state listener for exactly its visible lifetime. */
final class PendingQueueDialog {
  static Dialog show(Activity activity,RemoteApp app) {
    TurnController turns=TurnController.of(app);
    TaskQueue<TurnController.PendingMessage> queue=turns.queue();
    int generation=app.connectionGeneration();
    LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll=new ScrollView(activity);scroll.addView(rows);
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("待发队列（最多 20 条）").setView(scroll).setNegativeButton("关闭",null).create();
    Runnable render=()->{
      if(queue!=turns.queue()||generation!=app.connectionGeneration()){dialog.dismiss();return;}
      rows.removeAllViews();
      Button send=new Button(activity);send.setText("显式发送下一条");send.setOnClickListener(v->turns.sendQueuedMessage());rows.addView(send);
      Button auto=new Button(activity);auto.setText(queue.sequential()?"暂停顺序发送":"启用顺序发送（成功完成后发下一条）");rows.addView(auto);
      auto.setOnClickListener(v->{
        if(queue.sequential()){turns.setSequential(false);}
        else new AlertDialog.Builder(activity).setTitle("启用顺序发送？")
            .setMessage("启用后从下一次成功完成开始发送。审批、错误、只读或断开会暂停；送达未知不会重试。")
            .setPositiveButton("启用",(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration()){turns.setSequential(true);}})
            .setNegativeButton("取消",null).show();
      });
      for(TaskQueue.Item<TurnController.PendingMessage> item:queue.snapshot()) {
        TextView label=new TextView(activity);label.setText(TaskQueue.label(item.status)+" · "+item.attachments.preview(item.text));rows.addView(label);
        if(item.status!=TaskQueue.Status.PENDING && item.status!=TaskQueue.Status.FAILED){
          TextView hint=new TextView(activity);hint.setText("保留发送记录；失败或送达未知须人工核对远端，本窗口不重试。");rows.addView(hint);continue;
        }
        LinearLayout actions=new LinearLayout(activity);rows.addView(actions);
        String[] names={"编辑","上移","下移","移除"};
        for(int i=0;i<names.length;i++) {
          if(item.status==TaskQueue.Status.FAILED&&i!=3)continue;
          final int action=i;Button button=new Button(activity);button.setText(names[i]);actions.addView(button);
          button.setOnClickListener(v->{
            if(queue!=turns.queue()||generation!=app.connectionGeneration())return;
            if(action==1||action==2){queue.move(item,action==1?-1:1);app.changed();}
            else if(action==0){
              EditText edit=new EditText(activity);edit.setText(item.text);
              new AlertDialog.Builder(activity).setTitle("编辑待发文字（保留附件）").setView(edit)
                  .setPositiveButton("保存",(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration()){
                    if(edit.getText().toString().trim().isEmpty()&&item.attachments.images.isEmpty()&&item.attachments.files.isEmpty())return;
                    queue.edit(item,edit.getText().toString());app.changed();}}).setNegativeButton("取消",null).show();
            }else new AlertDialog.Builder(activity).setTitle("移除待发消息？").setMessage("附件返还当前草稿。")
                .setPositiveButton("移除",(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration())turns.remove(item);})
                .setNegativeButton("取消",null).show();
          });
        }
      }
    };
    dialog.setOnDismissListener(d->app.stateListeners.remove(render));
    app.stateListeners.add(render);dialog.show();render.run();return dialog;
  }
}
