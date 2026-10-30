"""Loopback-only production SftpContent integration, reusing listing fixture harness."""
from pathlib import Path
import tempfile, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'tools/test_sftp_listing.py').read_text()
s=s.replace("from test_history_navigation import method", "from test_history_navigation import method")
s=s.replace("        listener=socket.socket()",'''            def open(self,path,flags,attr):
                try:
                    fd=os.open(self.local(path),flags,0o600)
                    mode='r+b' if flags & os.O_RDWR else 'wb' if flags & os.O_WRONLY else 'rb'
                    f=os.fdopen(fd,mode);h=paramiko.SFTPHandle(flags)
                    h.readfile=f;h.writefile=f;return h
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
            def remove(self,path):
                try:self.local(path).unlink();return paramiko.SFTP_OK
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
            def rename(self,old,new):
                try:os.replace(self.local(old),self.local(new));return paramiko.SFTP_OK
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
            def chattr(self,path,attr):
                try:paramiko.SFTPServer.set_file_attr(str(self.local(path)),attr);return paramiko.SFTP_OK
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
        listener=socket.socket()''')
a=s.index(' RemoteFiles files=transport.listFiles');b=s.index(' }finally{s.disconnect();}',a)
s=s[:a]+''' SftpContent content=new SftpContent(transport::sftp);
 SftpContent.Guard guard=()->{};
 SftpContent.Text text=content.read("/workspace/notes.txt",guard);
 check(text.value.equals("hello"),"production UTF-8 preview");
 byte[] payload=("hello"+(char)13+(char)10+"中文"+(char)13+(char)10).getBytes(StandardCharsets.UTF_8);
 check(payload[5]==13 && payload[6]==10,"fixture contains actual CRLF bytes");
 content.write("/workspace/notes.txt",new ByteArrayInputStream(payload),262144,text.snapshot,guard);
 check(content.read("/workspace/notes.txt",guard).value.equals(new String(payload,StandardCharsets.UTF_8)),"UTF-8 CRLF roundtrip");
 boolean conflict=false;try{content.write("/workspace/notes.txt",new ByteArrayInputStream(new byte[]{1}),10,text.snapshot,guard);}catch(IOException e){conflict=true;}
 check(conflict,"stale snapshot rejected");
 SftpContent.Snapshot current=content.inspect("/workspace/notes.txt",guard);
 boolean failed=false;try{content.write("/workspace/notes.txt",new InputStream(){int n;public int read()throws IOException{if(n++>4)throw new IOException("fixture failure");return 97;}},100,current,guard);}catch(IOException e){failed=true;}
 check(failed && current.same(content.inspect("/workspace/notes.txt",guard)),"failed staging preserves original");
 byte[] binary=new byte[]{0,1,2,(byte)255};
 content.write("/workspace/binary",new ByteArrayInputStream(binary),100,content.inspect("/workspace/binary",guard),guard);
 ByteArrayOutputStream output=new ByteArrayOutputStream();content.download("/workspace/binary",output,guard);
 check(Arrays.equals(binary,output.toByteArray()),"binary upload and download payload");
 boolean rejected=false;try{content.read("/workspace/binary",guard);}catch(IOException e){rejected=true;}check(rejected,"binary preview rejected");
 byte[] boundary=new byte[SftpContent.TEXT_LIMIT];Arrays.fill(boundary,(byte)97);
 content.write("/workspace/boundary",new ByteArrayInputStream(boundary),boundary.length,content.inspect("/workspace/boundary",guard),guard);
 check(content.read("/workspace/boundary",guard).value.length()==boundary.length,"text limit accepted");
 content.write("/workspace/large",new ByteArrayInputStream(new byte[boundary.length+1]),boundary.length+1,content.inspect("/workspace/large",guard),guard);
 rejected=false;try{content.read("/workspace/large",guard);}catch(IOException e){rejected=true;}check(rejected,"oversize preview rejected");
 rejected=false;try{SftpContent.decode(new byte[]{(byte)0xff});}catch(IOException e){rejected=true;}check(rejected,"malformed UTF-8 rejected");
 rejected=false;try{content.write("/workspace/notes.txt",new ByteArrayInputStream(payload),100,current,()->{throw new IOException("generation changed");});}catch(IOException e){rejected=true;}
 check(rejected && current.same(content.inspect("/workspace/notes.txt",guard)),"generation cancellation preserves destination");
 String wildcard="/workspace/name*?.txt";
 byte[] keep="preserve".getBytes(StandardCharsets.UTF_8);
 content.write("/workspace/nameAB.txt",new ByteArrayInputStream(keep),100,content.inspect("/workspace/nameAB.txt",guard),guard);
 content.write(wildcard,new ByteArrayInputStream(payload),100,content.inspect(wildcard,guard),guard);
 check(content.read(wildcard,guard).value.equals(new String(payload,StandardCharsets.UTF_8)),"literal wildcard filename roundtrip");
 check(content.read("/workspace/nameAB.txt",guard).value.equals("preserve"),"literal filename never overwrites glob match");
 for(String special:Arrays.asList("/workspace/中文 [1].txt","/workspace/slash"+(char)92+"name.txt")) {
   content.write(special,new ByteArrayInputStream(payload),100,content.inspect(special,guard),guard);
   check(content.read(special,guard).value.equals(new String(payload,StandardCharsets.UTF_8)),"special filename exact roundtrip");
 }
 check(transport.listFiles("/workspace").entries.stream().noneMatch(e->e.name.contains(".langpt-")),"staging cleaned after failure");
 content.cancel();rejected=false;try{content.read("/workspace/notes.txt",guard);}catch(IOException e){rejected=true;}
 check(rejected,"cancelled content session never opens a new channel");
''' +s[b:]
s=s.replace("str(SRC/'RemoteFiles.java'),str(java)","str(SRC/'RemoteFiles.java'),str(SRC/'SftpContent.java'),str(java)")
# Execute harness with original location for ROOT and imports, without writing shared test.
exec(compile(s,str(ROOT/'tools/test_sftp_listing.py'),'exec'),{'__file__':str(ROOT/'tools/test_sftp_listing.py'),'__name__':'__main__'})
