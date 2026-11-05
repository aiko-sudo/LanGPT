"""Pure JVM coverage of directory verification and reply-time formatting."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'

class ProjectReplyTest(unittest.TestCase):
    def test_directory_checks_and_reply_dates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = (SRC / 'RemoteApp.java').read_text()
            start = source.index('  public String currentProject()')
            end = source.index('\n  }', start) + len('\n  }')
            project_method = source[start:end]
            files = {
                'app/remotessh/RemoteApp.java': 'package app.remotessh; class RemoteApp {Profile profile;boolean homeVisible;String threadId="",threadCwd="";'+project_method+'} class Profile {String cwd="";}',
                'app/remotessh/ProjectHistoryFilter.java': (SRC / 'ProjectHistoryFilter.java').read_text(),
                'app/remotessh/ProjectDirectoryCheck.java': (SRC / 'ProjectDirectoryCheck.java').read_text(),
                'app/remotessh/ReplyTimestamp.java': (SRC / 'ReplyTimestamp.java').read_text(),
                'android/content/Context.java': '''package android.content; public class Context { public static final int MODE_PRIVATE=0; public Pref getSharedPreferences(String n,int m){return new Pref();} public static class Pref { public long getLong(String k,long d){return d;} public Editor edit(){return new Editor();}} public static class Editor {public Editor putLong(String k,long v){return this;}public void apply(){}}}''',
                'android/util/Base64.java': '''package android.util; public class Base64 {public static final int NO_WRAP=1,URL_SAFE=2; public static String encodeToString(byte[] b,int f){return java.util.Base64.getEncoder().encodeToString(b);}}''',
                'org/json/JSONObject.java': '''package org.json; public class JSONObject {public static final Object NULL=new Object(); java.util.Map<String,Object> m=new java.util.HashMap<>(); public JSONObject put(String k,Object v){m.put(k,v);return this;}public Object opt(String k){return m.get(k);} public JSONObject optJSONObject(String k){Object v=m.get(k);return v instanceof JSONObject?(JSONObject)v:null;}}''',
                'com/jcraft/jsch/SftpException.java': '''package com.jcraft.jsch; public class SftpException extends Exception {public int id; public SftpException(int x){id=x;}}''',
                'com/jcraft/jsch/ChannelSftp.java': '''package com.jcraft.jsch; public class ChannelSftp {public static final int SSH_FX_NO_SUCH_FILE=2; public boolean closed; public Attr stat(String p)throws SftpException {if(p.equals("missing"))throw new SftpException(2);if(p.equals("denied"))throw new SftpException(3);return new Attr(!p.equals("file"));}public void disconnect(){closed=true;} public static class Attr {boolean directory;Attr(boolean b){directory=b;}public boolean isDir(){return directory;}}}''',
                'app/remotessh/SshTransport.java': '''package app.remotessh; class SshTransport {com.jcraft.jsch.ChannelSftp channel=new com.jcraft.jsch.ChannelSftp(); com.jcraft.jsch.ChannelSftp sftp(){return channel;}}''',
                'app/remotessh/Fixture.java': '''package app.remotessh; import java.util.*; class Fixture { public static void main(String[] a)throws Exception {TimeZone.setDefault(TimeZone.getTimeZone("UTC"));if(ReplyTimestamp.parse(new org.json.JSONObject().put("completedAt",1000))!=1000000)throw new AssertionError("seconds");if(ReplyTimestamp.parse(new org.json.JSONObject().put("timestamp","2026-11-05T09:03:00Z"))!=1793869380000L)throw new AssertionError("iso");long t=java.time.Instant.parse("2026-11-05T09:03:00Z").toEpochMilli();if(!ReplyTimestamp.format(t,t,Locale.US).equals("09:03"))throw new AssertionError("today");if(!ReplyTimestamp.format(t,t+86400000,Locale.US).equals("2026-11-05 09:03"))throw new AssertionError("older");if(!ReplyTimestamp.format(0,t,Locale.US).equals(""))throw new AssertionError("unknown");RemoteApp app=new RemoteApp();app.profile=new Profile();app.profile.cwd="/configured";app.threadId="thread";app.threadCwd="/other";app.homeVisible=true;if(!app.currentProject().equals("/configured"))throw new AssertionError("home project");app.homeVisible=false;if(!app.currentProject().equals("/other"))throw new AssertionError("thread project");app.threadCwd="";if(!app.currentProject().equals("/configured"))throw new AssertionError("empty thread cwd");ProjectHistoryFilter filter=new ProjectHistoryFilter();Map<String,Integer> old=filter.begin(Arrays.asList("/a"));Map<String,Integer> fresh=filter.begin(Arrays.asList("/a"));filter.finish(fresh,Collections.singletonMap("/a",true));filter.finish(old,Collections.singletonMap("/a",false));if(filter.missing("/a"))throw new AssertionError("stale result");Map<String,Integer> next=filter.begin(Arrays.asList("/a"));filter.finish(next,Collections.singletonMap("/a",false));if(!filter.missing("/a"))throw new AssertionError("missing");next=filter.begin(Arrays.asList("/a"));filter.finish(next,Collections.singletonMap("/a",true));if(filter.missing("/a"))throw new AssertionError("restored");next=filter.begin(Arrays.asList("/a"));filter.finish(next,null);if(filter.missing("/a"))throw new AssertionError("unknown");app.threadId="";app.threadCwd="/chosen";app.homeVisible=false;if(!app.currentProject().equals("/chosen"))throw new AssertionError("draft project");SshTransport ssh=new SshTransport();Map<String,Boolean> r=ProjectDirectoryCheck.check(ssh,Arrays.asList("folder","file","missing","denied"));if(!Boolean.TRUE.equals(r.get("folder"))||!Boolean.FALSE.equals(r.get("file"))||!Boolean.FALSE.equals(r.get("missing"))||r.containsKey("denied")||!ssh.channel.closed)throw new AssertionError(r);}}''',
            }
            for name, body in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body)
            subprocess.run(['javac', '-d', str(root / 'classes')] + [str(root / f) for f in files], check=True)
            subprocess.run(['java', '-cp', str(root / 'classes'), 'app.remotessh.Fixture'], check=True)

if __name__ == '__main__': unittest.main()
