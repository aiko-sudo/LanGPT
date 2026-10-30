#!/usr/local/bin/python3
"""Offline javac/JVM checks; never starts Gradle, SSH, Android or model services."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'app/src/main/java/app/remotessh'
with tempfile.TemporaryDirectory(prefix='langpt-pending-') as work:
    work = Path(work)
    subprocess.run(['javac', '-d', str(work / 'jvm'), *[str(MAIN / name) for name in
                    ('TaskQueue.java', 'TaskQueueRegistry.java', 'PromptTemplateStore.java')],
                    str(ROOT / 'tests/pending/PendingQueueJvmTest.java')], check=True)
    subprocess.run(['java', '-cp', str(work / 'jvm'), 'app.remotessh.PendingQueueJvmTest'], check=True)
    # Type checking only: Android's aapt-generated R is unavailable without a build.
    # These temporary resource constants cannot validate packaging, resources or UI.
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java'))
    resources = {}
    for source in sources:
        for kind, name in re.findall(r'\bR\.(\w+)\.(\w+)', source.read_text()):
            resources.setdefault(kind, set()).add(name)
    stub = work / 'R.java'
    stub.write_text('package app.remotessh; public final class R {' + ''.join(
        'public static final class ' + kind + ' {' + ''.join(
            f'public static final int {name}={i+1};' for i, name in enumerate(sorted(names))) + '}'
        for kind, names in resources.items()) + '}')
    cache = (Path.home() / '.gradle/caches')
    dependencies = ['/com.github.mwiede/jsch/2.28.0/', '/org.bouncycastle/bcprov-jdk18on/1.80/',
                    '/org.json/json/20240303/']
    jars = [str(p) for p in cache.rglob('*.jar') if any(part in str(p) for part in dependencies)]
    jars += [str(p) for p in cache.rglob('jlatexmath-android-0.2.0-api.jar')]
    jars.insert(0, str(Path(__import__('os').environ.get('ANDROID_HOME') or __import__('os').environ.get('ANDROID_SDK_ROOT') or str(Path.home() / 'Android/Sdk')) / 'platforms/android-36/android.jar'))
    subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', ':'.join(jars),
                    '-d', str(work / 'android-types'), str(stub), *map(str, sources)], check=True)
    print('PASS Java type check (temporary R constants; not an Android build)')
