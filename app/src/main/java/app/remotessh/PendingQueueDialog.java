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
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_queue_title)).setView(scroll).setNegativeButton(UiText.get(R.string.extra_close),null).create();
    Runnable render=()->{
      if(queue!=turns.queue()||generation!=app.connectionGeneration()){dialog.dismiss();return;}
      rows.removeAllViews();
      Button send=new Button(activity);send.setText(UiText.get(R.string.extra_queue_send_next));send.setOnClickListener(v->turns.sendQueuedMessage());rows.addView(send);
      Button auto=new Button(activity);auto.setText(queue.sequential()?UiText.get(R.string.extra_queue_pause):UiText.get(R.string.extra_queue_enable_desc));rows.addView(auto);
      auto.setOnClickListener(v->{
        if(queue.sequential()){turns.setSequential(false);}
        else new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_queue_enable_title))
            .setMessage(UiText.get(R.string.extra_queue_enable_message))
            .setPositiveButton(UiText.get(R.string.extra_enable),(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration()){turns.setSequential(true);}})
            .setNegativeButton(UiText.get(R.string.extra_cancel),null).show();
      });
      for(TaskQueue.Item<TurnController.PendingMessage> item:queue.snapshot()) {
        TextView label=new TextView(activity);label.setText(TaskQueue.label(item.status)+" · "+item.attachments.preview(item.text));rows.addView(label);
        if(item.status!=TaskQueue.Status.PENDING && item.status!=TaskQueue.Status.FAILED){
          TextView hint=new TextView(activity);hint.setText(UiText.get(R.string.extra_queue_history));rows.addView(hint);continue;
        }
        LinearLayout actions=new LinearLayout(activity);rows.addView(actions);
        String[] names={UiText.get(R.string.extra_edit),UiText.get(R.string.extra_move_up),UiText.get(R.string.extra_move_down),UiText.get(R.string.extra_remove)};
        for(int i=0;i<names.length;i++) {
          if(item.status==TaskQueue.Status.FAILED&&i!=3)continue;
          final int action=i;Button button=new Button(activity);button.setText(names[i]);actions.addView(button);
          button.setOnClickListener(v->{
            if(queue!=turns.queue()||generation!=app.connectionGeneration())return;
            if(action==1||action==2){queue.move(item,action==1?-1:1);app.changed();}
            else if(action==0){
              EditText edit=new EditText(activity);edit.setText(item.text);
              new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_queue_edit)).setView(edit)
                  .setPositiveButton(UiText.get(R.string.extra_save),(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration()){
                    if(edit.getText().toString().trim().isEmpty()&&item.attachments.images.isEmpty()&&item.attachments.files.isEmpty())return;
                    queue.edit(item,edit.getText().toString());app.changed();}}).setNegativeButton(UiText.get(R.string.extra_cancel),null).show();
            }else new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_queue_remove_title)).setMessage(UiText.get(R.string.extra_queue_remove_message))
                .setPositiveButton(UiText.get(R.string.extra_remove),(d,n)->{if(queue==turns.queue()&&generation==app.connectionGeneration())turns.remove(item);})
                .setNegativeButton(UiText.get(R.string.extra_cancel),null).show();
          });
        }
      }
    };
    dialog.setOnDismissListener(d->app.stateListeners.remove(render));
    app.stateListeners.add(render);dialog.show();render.run();return dialog;
  }
}
