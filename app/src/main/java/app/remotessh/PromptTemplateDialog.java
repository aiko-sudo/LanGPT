package app.remotessh;

import android.app.*;
import android.widget.*;
import java.util.*;

/** Draft-only template actions. Dialog callbacks reject changes of conversation/scope. */
final class PromptTemplateDialog {
  static void show(Activity activity,RemoteApp app,EditText draft,int fg,int muted) {
    if(app.profile==null)return;
    final String profile=app.profile.id,runtime=app.profile.runtime,path=app.currentProject(),thread=app.threadId;
    final int generation=app.connectionGeneration();
    java.util.function.BooleanSupplier valid=()->app.profile!=null && profile.equals(app.profile.id)
        && runtime.equals(app.profile.runtime)&&path.equals(app.currentProject())&&thread.equals(app.threadId)
        && generation==app.connectionGeneration()&&!activity.isFinishing()&&!activity.isDestroyed();
    try {
      PromptTemplateStore store=new PromptTemplateStore(new PromptTemplateStore.Storage(){
        public String read(String key)throws java.io.IOException {
          String value=CryptoBox.readSealed(activity,key);
          if(value==null&&CryptoBox.fileFor(activity,key).exists())throw new java.io.IOException(UiText.get(R.string.extra_template_decrypt));
          return value;
        }
        public void write(String key,String value)throws Exception {
          CryptoBox.writeSealed(activity,key,value);
          if(!value.equals(CryptoBox.readSealed(activity,key)))throw new java.io.IOException(UiText.get(R.string.extra_template_encrypt));
        }
      },profile,runtime,path);
      List<PromptTemplateStore.Template> values=store.list();
      String[] labels=new String[values.size()+1];labels[0]=UiText.get(R.string.extra_template_add);
      for(int i=0;i<values.size();i++)labels[i+1]=values.get(i).title;
      CenteredMenu.show(activity,fg,muted,UiText.get(R.string.extra_template_title),labels,n->{
        if(!valid.getAsBoolean())return;
        if(n==0){edit(activity,store,null,draft.getText().toString(),valid);return;}
        PromptTemplateStore.Template t=values.get(n-1);
        CenteredMenu.show(activity,fg,muted,t.title,new String[]{UiText.get(R.string.extra_template_fill),UiText.get(R.string.extra_edit),UiText.get(R.string.extra_delete)},action->{
          if(!valid.getAsBoolean())return;
          if(action==0){
            if(draft.getText().length()==0)draft.setText(t.text);
            else new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_template_draft_exists))
                .setPositiveButton(UiText.get(R.string.extra_replace),(a,b)->{if(valid.getAsBoolean())draft.setText(t.text);})
                .setNeutralButton(UiText.get(R.string.extra_append),(a,b)->{if(valid.getAsBoolean())draft.setText(PromptTemplateStore.fill(draft.getText().toString(),t.text,true));})
                .setNegativeButton(UiText.get(R.string.extra_cancel),null).show();
          }else if(action==1)edit(activity,store,t,t.text,valid);
          else new AlertDialog.Builder(activity).setTitle(UiText.get(R.string.extra_template_delete)).setMessage(t.title)
              .setPositiveButton(UiText.get(R.string.extra_delete),(a,b)->{if(valid.getAsBoolean())try{store.delete(t.id);}catch(Exception e){error(activity,e);}})
              .setNegativeButton(UiText.get(R.string.extra_cancel),null).show();
        });
      });
    }catch(Exception e){error(activity,e);}
  }
  private static void edit(Activity a,PromptTemplateStore store,PromptTemplateStore.Template t,String text,java.util.function.BooleanSupplier valid) {
    LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);
    EditText title=new EditText(a),body=new EditText(a);title.setHint(UiText.get(R.string.extra_template_name));body.setHint(UiText.get(R.string.extra_template_body));
    title.setText(t==null?"":t.title);body.setText(text);form.addView(title);form.addView(body);
    AlertDialog dialog=new AlertDialog.Builder(a).setTitle(UiText.get(R.string.extra_template_save)).setView(form)
        .setPositiveButton(UiText.get(R.string.extra_save),null).setNegativeButton(UiText.get(R.string.extra_cancel),null).create();
    dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      if(!valid.getAsBoolean()){dialog.dismiss();return;}
      try{store.save(t==null?null:t.id,title.getText().toString(),body.getText().toString());dialog.dismiss();}
      catch(Exception e){error(a,e);}
    }));dialog.show();
  }
  private static void error(Activity a,Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
}
