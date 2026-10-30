package app.remotessh;

import java.util.*;

/** Main-thread owned bounded queue. In-flight work is never returned to pending. */
final class TaskQueue<T> {
  enum Status { PENDING, SENDING, RUNNING, UNKNOWN, FAILED }
  static String label(Status status) {
    switch(status) {
      case PENDING:return "待发送";
      case SENDING:return "提交中（尚未确认）";
      case RUNNING:return "已接受，等待完成";
      case UNKNOWN:return "送达未知（禁止重试）";
      default:return "已确认失败 / 中断";
    }
  }
  static final int LIMIT = 20;
  static final class Item<T> {
    final String id = UUID.randomUUID().toString();
    String text;
    final T attachments;
    Status status = Status.PENDING;
    String turnId="", completedId="";
    boolean completedSuccessfully;
    Item(String text, T attachments) { this.text=text; this.attachments=attachments; }
  }
  private final List<Item<T>> items = new ArrayList<>();
  private boolean sequential;
  boolean add(String text,T attachments) {
    if(items.size()>=LIMIT)return false;
    items.add(new Item<>(text,attachments));return true;
  }
  List<Item<T>> snapshot(){return Collections.unmodifiableList(new ArrayList<>(items));}
  Item<T> first(){return items.isEmpty()?null:items.get(0);}
  boolean edit(Item<T> item,String text){
    if(!items.contains(item)||item.status!=Status.PENDING)return false;
    item.text=text;return true;
  }
  boolean move(Item<T> item,int delta){
    int i=items.indexOf(item),j=i+delta;
    if(i<0||j<0||j>=items.size()||item.status!=Status.PENDING||items.get(j).status!=Status.PENDING)return false;
    Collections.swap(items,i,j);return true;
  }
  Item<T> remove(Item<T> item){
    return (item.status==Status.PENDING||item.status==Status.FAILED) && items.remove(item)?item:null;
  }
  Item<T> begin(){
    Item<T> item=first();
    if(item==null||item.status!=Status.PENDING)return null;
    item.status=Status.SENDING;return item;
  }
  void accepted(Item<T> item,String turnId){
    if(!items.contains(item)||item.status!=Status.SENDING)return;
    if(turnId.isEmpty()){unknown(item);return;}
    item.turnId=turnId;item.status=Status.RUNNING;settle(item);
  }
  void outcome(String id,boolean success,boolean historical) {
    Item<T> item=first();
    if(historical||id.isEmpty()||item==null||(item.status!=Status.SENDING&&item.status!=Status.RUNNING))return;
    if(!item.turnId.isEmpty()&&!item.turnId.equals(id))return;
    item.completedId=id;item.completedSuccessfully=success;settle(item);
  }
  private void settle(Item<T> item) {
    if(item.turnId.isEmpty()||!item.turnId.equals(item.completedId))return;
    if(item.completedSuccessfully)completed();else failed();
  }
  void unknown(Item<T> item){if(items.contains(item)){item.status=Status.UNKNOWN;pause();}}
  void completed(){
    Item<T> item=first();
    if(item!=null&&(item.status==Status.SENDING||item.status==Status.RUNNING))items.remove(0);
  }
  void failed(){
    Item<T> item=first();
    if(item!=null&&(item.status==Status.SENDING||item.status==Status.RUNNING))item.status=Status.FAILED;
    pause();
  }
  void disconnect(){
    Item<T> item=first();
    if(item!=null&&(item.status==Status.SENDING||item.status==Status.RUNNING))item.status=Status.UNKNOWN;
    pause();
  }
  void observeSafety(boolean connected,boolean readOnly,boolean replaying,boolean approval,boolean error,boolean stopping) {
    if(!connected)disconnect();
    else if(readOnly||replaying||approval||error||stopping)pause();
  }
  void pause(){sequential=false;}
  boolean sequential(){return sequential;}
  void enable(){sequential=true;}
}
