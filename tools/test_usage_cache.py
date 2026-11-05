"""Offline JVM fixture for per-profile automatic quota throttling."""
import pathlib, subprocess, tempfile, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
class UsageCacheTest(unittest.TestCase):
 def test_throttle_manual_and_profile_isolation(self):
  with tempfile.TemporaryDirectory() as tmp:
   p=pathlib.Path(tmp)
   files={
    'app/remotessh/UsageCache.java':(ROOT/'app/src/main/java/app/remotessh/UsageCache.java').read_text(),
    'android/content/Context.java':'''package android.content; public class Context { public static final int MODE_PRIVATE=0; final SharedPreferences p=new SharedPreferences();public SharedPreferences getSharedPreferences(String n,int m){return p;} }''',
    'android/content/SharedPreferences.java':'''package android.content; import java.util.*;public class SharedPreferences {Map<String,Object> m=new HashMap<>();public long getLong(String k,long d){return m.containsKey(k)?(Long)m.get(k):d;}public String getString(String k,String d){return m.containsKey(k)?(String)m.get(k):d;}public Editor edit(){return new Editor();}public class Editor {public Editor putLong(String k,long v){m.put(k,v);return this;}public Editor putString(String k,String v){m.put(k,v);return this;}public void apply(){}}}''',
    'org/json/JSONException.java':'package org.json; public class JSONException extends Exception {}',
    'org/json/JSONObject.java':'''package org.json; public class JSONObject {String v;public JSONObject(String v)throws JSONException{this.v=v;}public String toString(){return v;}}''',
    'app/remotessh/Fixture.java':'''package app.remotessh; import org.json.*;class Profile {String id="a",runtime="codex";}class UsageLimits {long fetchedAt;String limits="",stats="";void update(JSONObject j,boolean s){limits=j.toString();}void updateStats(JSONObject j){stats=j.toString();}}class Fixture {public static void main(String[] x)throws Exception{UsageCache c=new UsageCache(new android.content.Context());Profile p=new Profile();if(!c.claim(p,"limits",false)||c.claim(p,"limits",false)||!c.claim(p,"limits",true)||!c.claim(p,"stats",false))throw new AssertionError("throttle");c.save(p,"limits",new JSONObject("quota"));c.save(p,"stats",new JSONObject("stats"));UsageLimits u=new UsageLimits();c.restore(p,u);if(!u.limits.equals("quota")||!u.stats.equals("stats")||u.fetchedAt==0)throw new AssertionError("restore");p.id="b";if(!c.claim(p,"limits",false))throw new AssertionError("profile isolation");UsageLimits v=new UsageLimits();c.restore(p,v);if(!v.limits.isEmpty())throw new AssertionError("cache leak");}}'''}
   for name,body in files.items():
    f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body)
   subprocess.run(['javac','-d',str(p/'out')]+[str(p/n) for n in files],check=True)
   subprocess.run(['java','-cp',str(p/'out'),'app.remotessh.Fixture'],check=True)
if __name__=='__main__': unittest.main()
