package app.remotessh;

import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Storage-independent template logic; encrypted adapter is supplied by Android UI. */
final class PromptTemplateStore {
  interface Storage { String read(String key) throws IOException; void write(String key,String value) throws Exception; }
  static final class Template {
    final String id,title,text;
    Template(String id,String title,String text){this.id=id;this.title=title;this.text=text;}
  }
  private final Storage storage;
  final String key;
  PromptTemplateStore(Storage storage,String profile,String runtime,String path) {
    this.storage=storage;
    if(profile.isEmpty()||runtime.isEmpty()||!path.startsWith("/"))throw new IllegalArgumentException("需要已确定的绝对项目路径");
    key=scope(profile,runtime,path);
  }
  static String scope(String profile,String runtime,String path) {
    Deque<String> parts=new ArrayDeque<>();
    for(String part:path.split("/")) {
      if(part.isEmpty()||part.equals("."))continue;
      if(part.equals("..")){if(!parts.isEmpty())parts.removeLast();}else parts.addLast(part);
    }
    String canonical="/"+String.join("/",parts);
    try {
      String value=profile.length()+":"+profile+runtime.length()+":"+runtime+canonical;
      byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out=new StringBuilder("prompt-templates-v1-");
      for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
      return out.toString();
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  List<Template> list() throws IOException {
    String encoded=storage.read(key);
    if(encoded==null)return new ArrayList<>();
    try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
      int count=in.readInt();if(count<0||count>50)throw new IOException("模板数据无效");
      List<Template> result=new ArrayList<>();
      for(int i=0;i<count;i++)result.add(new Template(in.readUTF(),in.readUTF(),in.readUTF()));
      return result;
    }catch(IllegalArgumentException e){throw new IOException("模板数据无效",e);}
  }
  void save(String id,String title,String text) throws Exception {
    if(title.trim().isEmpty()||text.trim().isEmpty())throw new IOException("标题和内容不能为空");
    List<Template> values=list();
    if(id!=null)values.removeIf(t->t.id.equals(id));
    if(values.size()>=50)throw new IOException("最多 50 个模板");
    values.add(new Template(id==null?UUID.randomUUID().toString():id,title,text));write(values);
  }
  void delete(String id)throws Exception {List<Template> values=list();values.removeIf(t->t.id.equals(id));write(values);}
  private void write(List<Template> values)throws Exception {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    try(DataOutputStream out=new DataOutputStream(bytes)) {
      out.writeInt(values.size());for(Template t:values){out.writeUTF(t.id);out.writeUTF(t.title);out.writeUTF(t.text);}
    }
    storage.write(key,Base64.getEncoder().encodeToString(bytes.toByteArray()));
  }
  static String fill(String draft,String template,boolean append){return append&&!draft.isEmpty()?draft+"\n"+template:template;}
}
