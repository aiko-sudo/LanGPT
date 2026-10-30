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
          if(value==null&&CryptoBox.fileFor(activity,key).exists())throw new java.io.IOException("模板无法解密，保留原文件");
          return value;
        }
        public void write(String key,String value)throws Exception {
          CryptoBox.writeSealed(activity,key,value);
          if(!value.equals(CryptoBox.readSealed(activity,key)))throw new java.io.IOException("加密保存失败");
        }
      },profile,runtime,path);
      List<PromptTemplateStore.Template> values=store.list();
      String[] labels=new String[values.size()+1];labels[0]="新增 / 保存当前草稿";
      for(int i=0;i<values.size();i++)labels[i+1]=values.get(i).title;
      CenteredMenu.show(activity,fg,muted,"项目提示词模板",labels,n->{
        if(!valid.getAsBoolean())return;
        if(n==0){edit(activity,store,null,draft.getText().toString(),valid);return;}
        PromptTemplateStore.Template t=values.get(n-1);
        CenteredMenu.show(activity,fg,muted,t.title,new String[]{"填入草稿","编辑","删除"},action->{
          if(!valid.getAsBoolean())return;
          if(action==0){
            if(draft.getText().length()==0)draft.setText(t.text);
            else new AlertDialog.Builder(activity).setTitle("草稿已有内容")
                .setPositiveButton("替换",(a,b)->{if(valid.getAsBoolean())draft.setText(t.text);})
                .setNeutralButton("追加",(a,b)->{if(valid.getAsBoolean())draft.setText(PromptTemplateStore.fill(draft.getText().toString(),t.text,true));})
                .setNegativeButton("取消",null).show();
          }else if(action==1)edit(activity,store,t,t.text,valid);
          else new AlertDialog.Builder(activity).setTitle("删除模板？").setMessage(t.title)
              .setPositiveButton("删除",(a,b)->{if(valid.getAsBoolean())try{store.delete(t.id);}catch(Exception e){error(activity,e);}})
              .setNegativeButton("取消",null).show();
        });
      });
    }catch(Exception e){error(activity,e);}
  }
  private static void edit(Activity a,PromptTemplateStore store,PromptTemplateStore.Template t,String text,java.util.function.BooleanSupplier valid) {
    LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);
    EditText title=new EditText(a),body=new EditText(a);title.setHint("模板标题");body.setHint("提示词内容");
    title.setText(t==null?"":t.title);body.setText(text);form.addView(title);form.addView(body);
    AlertDialog dialog=new AlertDialog.Builder(a).setTitle("保存项目模板").setView(form)
        .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
    dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      if(!valid.getAsBoolean()){dialog.dismiss();return;}
      try{store.save(t==null?null:t.id,title.getText().toString(),body.getText().toString());dialog.dismiss();}
      catch(Exception e){error(a,e);}
    }));dialog.show();
  }
  private static void error(Activity a,Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
}
