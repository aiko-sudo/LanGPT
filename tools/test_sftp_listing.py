"""Real JSch -> Paramiko SFTP fixture round trip; no production host or credentials.
Exercises the exact production listFiles method and RemoteFiles model on JVM.
"""
from pathlib import Path
import os
import hashlib
import base64
import socket
import subprocess
import tempfile
import threading
import time
import paramiko
from test_history_navigation import method

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
import testdeps
JAR=Path(testdeps.require(testdeps.jsch_jar(),'jsch'))

def main():
    with tempfile.TemporaryDirectory(dir=ROOT/'tools',prefix='sftp-test-', ignore_cleanup_errors=True) as tmp:
        tmp=Path(tmp);disk=tmp/'remote';disk.mkdir();work=disk/'workspace';work.mkdir()
        (work/'folder').mkdir();(work/'folder'/'child.txt').write_text('child')
        (work/'.hidden').write_text('hidden');(work/'notes.txt').write_text('hello')
        for i in range(1001): (work/f'item-{i:04}.txt').write_text('x')
        os.symlink('notes.txt',work/'notes-link')
        class Auth(paramiko.ServerInterface):
            def check_auth_password(self, username, password):
                return paramiko.AUTH_SUCCESSFUL if username=='fixture' and password=='fixture-only' else paramiko.AUTH_FAILED
            def get_allowed_auths(self,username):return 'password'
            def check_channel_request(self,kind,chanid):return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        class Files(paramiko.SFTPServerInterface):
            def local(self,path):
                p=disk/path.lstrip('/')
                p.resolve().relative_to(disk.resolve())
                return p
            def canonicalize(self,path):return '/' if path in ('','.','/') else '/' + path.lstrip('/')
            def list_folder(self,path):
                try:
                    result=[]
                    for p in self.local(path).iterdir():
                        a=paramiko.SFTPAttributes.from_stat(p.lstat());a.filename=p.name;result.append(a)
                    return result
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
            def stat(self,path):
                try:return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
            def lstat(self,path):
                try:return paramiko.SFTPAttributes.from_stat(self.local(path).lstat())
                except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
        listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1);listener.settimeout(.5)
        port=listener.getsockname()[1];stop=threading.Event();key=paramiko.RSAKey.generate(2048);errors=[]
        def serve():
            transport=None
            try:
                while not stop.is_set():
                    try:client,_=listener.accept();break
                    except socket.timeout:continue
                else:return
                transport=paramiko.Transport(client);transport.add_server_key(key)
                transport.set_subsystem_handler('sftp',paramiko.SFTPServer,Files)
                transport.start_server(server=Auth())
                while transport.is_active() and not stop.wait(.05):pass
            except Exception as e:
                if not stop.is_set():errors.append(e)
            finally:
                if transport:transport.close()
        thread=threading.Thread(target=serve,daemon=True);thread.start()
        source=(SRC/'SshTransport.java').read_text()
        listing=method(source,'  RemoteFiles listFiles(String path)')
        auth=method(source,'  void connectFiles(Profile p, HostPins vault, Trust trust, int timeoutMs)')
        # The current connection path opens the production socket directly; no reachable() preflight.
        java=tmp/'SftpRoundTripTest.java'
        java.write_text('''package app.remotessh;
import java.io.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import com.jcraft.jsch.*;
class Profile {
 String host="127.0.0.1",user="fixture",password="fixture-only",privateKey="",passphrase="",fallbackHost="";
 int port,fallbackPort;boolean tailscaleSsh;void validate(){}
}
class HostPins {
 String pin;HostPins(String pin){this.pin=pin;}String getPin(String host){return pin;}
 void rememberPin(String host,String fingerprint){throw new AssertionError("must use existing pin");}
}
class SshTransport {
 interface Trust {boolean confirm(String host,String fingerprint)throws Exception;}
 Session session;boolean closed;java.net.Socket connectingSocket;app.remotessh.tsnet.TsnetSocketFactory tsnetFactory;void phase(String text){}
 ChannelSftp sftp()throws Exception{ChannelSftp c=(ChannelSftp)session.openChannel("sftp");c.connect(5000);return c;}
''' + auth + listing + '''
}
public class SftpRoundTripTest {
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
 public static void main(String[] args)throws Exception {
 Profile profile=new Profile();profile.port=Integer.parseInt(args[0]);
 SshTransport transport=new SshTransport();
 transport.connectFiles(profile,new HostPins(args[1]),(host,fingerprint)->false,5000);
 Session s=transport.session;
 try {
 check(s.isConnected(),"production SSH-only authentication accepts matching saved host pin without agent startup");
 RemoteFiles files=transport.listFiles("/workspace");
 check(files.path.equals("/workspace"),"canonical project path returned over real SFTP");
 check(files.entries.size()==1005,"full listing exceeds old 1000-entry limit without truncation");
 check(files.entries.get(0).directory && files.entries.get(0).name.equals("folder"),"directories sorted first");
 check(files.entries.stream().anyMatch(e->e.name.equals(".hidden")),"hidden files included");
 check(files.entries.stream().anyMatch(e->e.name.equals("notes.txt")&&e.size==5&&!e.directory),"ordinary file metadata returned");
 check(files.entries.stream().anyMatch(e->e.name.equals("notes-link")&&e.link),"symlink identified without writing or following during listing");
 check(transport.listFiles("/workspace/folder").entries.get(0).name.equals("child.txt"),"nested folder navigation works");
 boolean denied=false;try{transport.listFiles("/workspace/notes.txt");}catch(IOException e){denied=true;}
 check(denied,"file path is not treated as directory");
 boolean missing=false;try{transport.listFiles("/missing");}catch(SftpException e){missing=true;}
 check(missing,"missing path reports SFTP error");
 check(transport.listFiles("/workspace").entries.size()==1005,"later read succeeds after errors and channel cleanup");
 }finally{s.disconnect();}
 }
}''')
        stub=tmp/'app/remotessh/tsnet/TsnetSocketFactory.java'
        stub.parent.mkdir(parents=True,exist_ok=True)
        stub.write_text('package app.remotessh.tsnet; public class TsnetSocketFactory { public java.net.Socket createSocket(String host,int port) throws java.io.IOException { throw new java.io.IOException("tsnet not available in direct SSH fixture"); } public void close() throws java.io.IOException {} }')
        try:
            subprocess.run(['javac','-cp',str(JAR),'-d',str(tmp),str(stub),str(SRC/'RemoteFiles.java'),str(java)],check=True)
            subprocess.run(['java','-cp',str(tmp)+os.pathsep+str(JAR),'app.remotessh.SftpRoundTripTest',str(port),'SHA256:'+base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')],check=True,timeout=40)
            assert not errors,errors
        finally:stop.set();listener.close();thread.join(3)
    print('SFTP fixture passed; production SSH login and Android page interaction remain untested.')

if __name__=='__main__':main()
