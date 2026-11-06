"""Exercise production model, tier and usage classes on a plain JVM."""
from pathlib import Path
import os
import subprocess
import tempfile
import testdeps

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/app/remotessh'
jar = testdeps.org_json_jar()
if not jar:
    raise SystemExit('FAIL: org.json jar unavailable')

HARNESS = r'''package app.remotessh;
import java.util.*;
import org.json.*;
public class Harness {
  static void eq(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError(a + " != " + b); }
  static void has(String a, String b) { if (!a.contains(b)) throw new AssertionError(a + " missing " + b); }
  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.US);
    JSONObject m = new JSONObject("{\"model\":\"wire-model\",\"displayName\":\"Server Model\",\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"high\"},\"low\",\"high\"],\"serviceTiers\":[{\"id\":\"priority\",\"name\":\"Server Priority\",\"description\":\"server text\"}]}");
    JSONArray models = new JSONArray().put(m);
    eq(ModelOptions.id(m), "wire-model");
    eq(ModelOptions.efforts(m), Arrays.asList("high", "low"));
    eq(ModelOptions.displayName(models, ""), "Default model");
    eq(ModelOptions.displayName(models, "wire-model"), "Server Model");
    eq(ModelOptions.label("high"), "High");
    eq(ModelOptions.label("custom"), "custom");
    eq(ServiceTierOptions.supportedId(m, "fast"), "priority");
    eq(ServiceTierOptions.label("default", m), "Standard");
    eq(ServiceTierOptions.label("fast", m), "Server Priority");
    eq(ServiceTierOptions.description("fast", m), "server text");
    JSONObject legacy = new JSONObject("{\"additionalSpeedTiers\":[\"fast\",\"fast\",\"default\"]}");
    eq(ServiceTierOptions.tiers(legacy).size(), 1);
    eq(ServiceTierOptions.supportedId(legacy, "priority"), "fast");
    UsageLimits u = new UsageLimits();
    has(u.summary(), "Connect to view");
    JSONObject payload = new JSONObject("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":20,\"windowDurationMins\":300},\"planType\":\"plus\",\"credits\":{\"hasCredits\":true,\"balance\":\"42\"}}}");
    u.update(payload, true);
    has(u.summary(), "Primary 80%"); has(u.details(), "Plan: plus"); has(u.details(), "Balance 42");
    u.update(new JSONObject("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":null,\"secondary\":{\"usedPercent\":30}}}"), false);
    has(u.summary(), "80%"); has(u.summary(), "Secondary 70%");
    u.updateStats(new JSONObject("{\"summary\":{\"lifetimeTokens\":1234567,\"currentStreakDays\":2,\"longestStreakDays\":3}}"));
    has(u.details(), "Lifetime: 1.2M"); has(u.details(), "Current streak: 2 days");
    u.fail(new Exception("offline")); has(u.summary(), "Update failed"); if (u.details().contains("Usage unavailable")) throw new AssertionError("single-line error");
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
    eq(ModelOptions.displayName(models, ""), "默认模型"); eq(ModelOptions.label("high"), "高");
    eq(ServiceTierOptions.label("default", m), "普通");
    has(u.summary(), "剩余用量"); has(u.summary(), "更新失败"); if (u.details().contains("用量暂不可用")) throw new AssertionError("single-line error zh"); has(u.details(), "累计：123.5万");
    u.clear(); has(u.summary(), "连接后查看剩余用量");
    Locale.setDefault(Locale.US); has(u.summary(), "Connect to view");
    UsageLimits.setLocaleProvider(() -> Locale.CHINESE);
    ModelOptions.setLocaleProvider(() -> Locale.CHINESE);
    ServiceTierOptions.setLocaleProvider(() -> Locale.CHINESE);
    has(u.summary(), "连接后查看剩余用量"); eq(ModelOptions.label("high"), "高");
    eq(ServiceTierOptions.label("default", m), "普通");
    UsageLimits.setLocaleProvider(null); ModelOptions.setLocaleProvider(null); ServiceTierOptions.setLocaleProvider(null);
    has(u.summary(), "Connect to view");
    System.out.println("PASS bilingual model, tier and usage production JVM behavior");
  }
}'''

with tempfile.TemporaryDirectory(prefix='model-usage-i18n-') as tmp:
    root = Path(tmp)
    pkg = root / 'app/remotessh'
    pkg.mkdir(parents=True)
    (pkg / 'Harness.java').write_text(HARNESS, encoding='utf-8')
    (pkg / 'ConnectionFailure.java').write_text('package app.remotessh; class ConnectionFailure { static String reason(Throwable t) { return t.getMessage(); } }', encoding='utf-8')
    sources = [SRC / (name + '.java') for name in ('UsageLimits', 'ModelOptions', 'ServiceTierOptions', 'Json')]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', jar, '-d', tmp, *map(str, sources), str(pkg / 'ConnectionFailure.java'), str(pkg / 'Harness.java')], check=True)
    subprocess.run(['java', '-cp', tmp + os.pathsep + jar, 'app.remotessh.Harness'], check=True)
