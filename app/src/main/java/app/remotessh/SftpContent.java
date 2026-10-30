package app.remotessh;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded file operations. Rename publication is server-dependent, never an atomic CAS. */
final class SftpContent {
  static final int TEXT_LIMIT=256*1024;
  static final long TRANSFER_LIMIT=100L*1024*1024;
  interface Guard { void check() throws Exception; }
  interface Channels { ChannelSftp open() throws Exception; }
  private static final java.util.concurrent.atomic.AtomicInteger operations = new java.util.concurrent.atomic.AtomicInteger();
  static boolean hasActiveOperations(){return operations.get()>0;}
  final Channels channels;
  private volatile ChannelSftp active;
  private volatile boolean cancelled;
  SftpContent(Channels channels){this.channels=channels;}
  void cancel(){cancelled=true;ChannelSftp channel=active;if(channel!=null)channel.disconnect();}
  private ChannelSftp open() throws Exception {
    if(cancelled)throw new IOException("操作已取消");
    ChannelSftp channel=channels.open();active=channel;
    if(cancelled){channel.disconnect();active=null;throw new IOException("操作已取消");}
    operations.incrementAndGet();
    return channel;
  }
  private void close(ChannelSftp channel){
    if(active==channel)active=null;
    try {channel.disconnect();} finally {operations.decrementAndGet();}
  }
  static final class Snapshot {
    final boolean exists; final long size; final int modified, permissions; final byte[] digest;
    Snapshot(boolean exists,long size,int modified,int permissions,byte[] digest){
      this.exists=exists;this.size=size;this.modified=modified;this.permissions=permissions;this.digest=digest;
    }
    boolean same(Snapshot other){return exists==other.exists && size==other.size && modified==other.modified
        && permissions==other.permissions && Arrays.equals(digest,other.digest);}
  }
  static final class Text {
    final String value; final Snapshot snapshot;
    Text(String value,Snapshot snapshot){this.value=value;this.snapshot=snapshot;}
  }
  static long copy(InputStream in,OutputStream out,long limit,Guard guard)throws Exception {
    byte[] buffer=new byte[16384];long total=0;int count;
    while(true){guard.check();count=in.read(buffer);if(count<0)break;
      total+=count;if(total>limit)throw new IOException("文件超过大小限制");guard.check();out.write(buffer,0,count);}
    guard.check();return total;
  }
  static String decode(byte[] bytes)throws IOException {
    try {
      String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      for(int i=0;i<value.length();i++){char c=value.charAt(i);
        if((c<32 && c!='\r' && c!='\n' && c!='\t') || c==127)throw new IOException("二进制文件，仅支持下载");}
      return value;
    }catch(CharacterCodingException e){throw new IOException("不是有效 UTF-8 文本，仅支持下载",e);}
  }
  // JSch public SFTP methods expand remote globs; filenames must stay literal.
  static String literal(String path) throws IOException {
    if(path==null || !path.startsWith("/") || path.indexOf('\0')>=0)throw new IOException("需要远端绝对路径");
    StringBuilder quoted=new StringBuilder();
    for(int i=0;i<path.length();i++) {
      char c=path.charAt(i);
      if(c=='\\' || c=='*' || c=='?' || c=='[' || c==']')quoted.append('\\');
      quoted.append(c);
    }
    return quoted.toString();
  }
  private Snapshot snapshot(ChannelSftp c,String path,Guard guard)throws Exception {
    guard.check();SftpATTRS a;
    try{a=c.lstat(literal(path));}catch(SftpException e){if(e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return new Snapshot(false,0,0,0,null);throw e;}
    if(!a.isReg())throw new IOException("仅支持普通文件（不跟随符号链接）");
    if(a.getSize()>TRANSFER_LIMIT)throw new IOException("文件超过 100 MiB 限制");
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=c.get(literal(path));OutputStream sink=new java.security.DigestOutputStream(new OutputStream(){public void write(int b){} public void write(byte[] b,int o,int n){}},digest)){
      copy(in,sink,TRANSFER_LIMIT,guard);
    }
    return new Snapshot(true,a.getSize(),a.getMTime(),a.getPermissions(),digest.digest());
  }
  Snapshot inspect(String path,Guard guard)throws Exception {
    guard.check();ChannelSftp c=open();try{return snapshot(c,path,guard);}finally{close(c);}
  }
  Text read(String path,Guard guard)throws Exception {
    guard.check();ChannelSftp c=open();try{
      SftpATTRS a=c.lstat(literal(path));if(!a.isReg() || a.getSize()>TEXT_LIMIT)throw new IOException("非普通文件或超过 256 KiB，仅支持下载");
      ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.get(literal(path))){copy(in,out,TEXT_LIMIT,guard);}
      byte[] bytes=out.toByteArray();String value=decode(bytes);
      Snapshot original=new Snapshot(true,bytes.length,a.getMTime(),a.getPermissions(),MessageDigest.getInstance("SHA-256").digest(bytes));
      if(!original.same(snapshot(c,path,guard)))throw new IOException("文件在读取期间改变，请重新打开");
      return new Text(value,original);
    }finally{close(c);}
  }
  void write(String path,InputStream input,long limit,Snapshot expected,Guard guard)throws Exception {
    guard.check();ChannelSftp c=open();String staged=path.substring(0,path.lastIndexOf('/')+1)+".langpt-"+UUID.randomUUID()+".tmp";
    try {
      if(!expected.same(snapshot(c,path,guard)))throw new IOException("远端文件已改变，请重新加载；未覆盖");
      try(OutputStream out=c.put(literal(staged),ChannelSftp.OVERWRITE)){copy(input,out,limit,guard);}
      c.chmod(expected.exists ? expected.permissions & 0777 : 0600,literal(staged));
      if(!expected.same(snapshot(c,path,guard)))throw new IOException("远端文件已改变，未覆盖");
      guard.check();c.rename(literal(staged),literal(path));
    }finally{try{c.rm(literal(staged));}catch(Exception ignored){}close(c);}
  }
  void download(String path,OutputStream out,Guard guard)throws Exception {
    guard.check();ChannelSftp c=open();try{
      SftpATTRS a=c.lstat(literal(path));if(!a.isReg() || a.getSize()>TRANSFER_LIMIT)throw new IOException("仅可下载 100 MiB 内普通文件");
      try(InputStream in=c.get(literal(path))){copy(in,out,TRANSFER_LIMIT,guard);}
    }finally{close(c);}
  }
}
