"""Execute production refreshUsage with a deterministic main queue and RPC callbacks."""
from pathlib import Path
import subprocess, tempfile, sys, os
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_history_navigation import method
import testdeps
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/app/remotessh'
source=(SRC/'RemoteApp.java').read_text()
refresh=method(source,'  public void refreshUsage()')
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp); pkg=p/'app/remotessh';pkg.mkdir(parents=True)
    (pkg/'ConnectionFailure.java').write_text('package app.remotessh; class ConnectionFailure { static String reason(Throwable t){return t.getMessage();} }')
    (pkg/'Harness.java').write_text('''package app.remotessh;
import org.json.JSONObject;import java.util.*;import java.util.function.*;
class Queue {List<Runnable> tasks=new ArrayList<>();void postDelayed(Runnable r,long delay){tasks.add(r);}void run(int i){tasks.get(i).run();}}
class Pending {Consumer<JSONObject> success;Consumer<Throwable> failure;Pending(Consumer<JSONObject>s,Consumer<Throwable>f){success=s;failure=f;}}
class App {boolean connected=true;UsageLimits usage=new UsageLimits();int usageAttempt;Queue main=new Queue();List<Pending> pending=new ArrayList<>();int paints;
void changed(){paints++;}void request(String m,JSONObject p,Consumer<JSONObject>s,Consumer<Throwable>f){pending.add(new Pending(s,f));}
'''+refresh+'''
}
public class Harness {static void check(boolean b){if(!b)throw new AssertionError();}
static JSONObject payload(int used)throws Exception{return new JSONObject().put("rateLimits",new JSONObject().put("primary",new JSONObject().put("usedPercent",used)));}
public static void main(String[] args)throws Exception {App a=new App();a.usage.update(payload(20),true);a.refreshUsage();check(a.usage.loading && a.paints==1);a.pending.get(0).failure.accept(new Exception("offline"));check(!a.usage.loading && a.usage.details().contains("offline") && a.usage.summary().contains("80%"));
a.refreshUsage();a.main.run(1);check(!a.usage.loading && a.usage.error.contains("超时"));a.refreshUsage();a.pending.get(1).success.accept(payload(90));a.pending.get(1).failure.accept(new Exception("old"));check(a.usage.loading && a.usage.summary().contains("80%"));a.pending.get(2).success.accept(payload(40));check(!a.usage.loading && a.usage.error.isEmpty() && a.usage.summary().contains("60%"));a.main.run(2);check(a.usage.summary().contains("60%"));System.out.println("PASS production refresh lifecycle");}}
''')
    jar=testdeps.require(testdeps.org_json_jar(),'org.json')
    subprocess.run(['javac','-encoding','UTF-8','-cp',jar,'-d',tmp,str(SRC/'UsageLimits.java'),str(pkg/'ConnectionFailure.java'),str(pkg/'Harness.java')],check=True)
    subprocess.run(['java','-cp',tmp+os.pathsep+jar,'app.remotessh.Harness'],check=True)
assert 'if (usageDialog == ownedUsageDialog)' in (SRC/'MainActivity.java').read_text()
assert 'usage.status =' not in source.split('if (method.equals("account/rateLimits/read")) {',1)[1].split('}',1)[0]
print('PASS dialog ownership and generic failure wiring')
