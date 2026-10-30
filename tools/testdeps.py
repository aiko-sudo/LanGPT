"""Shared dependency resolution for JVM tests.

Resolution order for each jar: explicit env override (CI pins versions by
downloading them), then the local Gradle cache, then None — tests print a
skip line and exit 0 when None so environments without the jar do not fake
a red failure."""
import os
from pathlib import Path


def _first_existing_env(*names):
    for name in names:
        value = os.environ.get(name)
        if value and Path(value).is_file():
            return value
    return None


def _cache_jar(group, artifact, prefix):
    root = Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact
    if not root.is_dir():
        return None
    jars = sorted(root.rglob(prefix + '-*.jar'))
    return str(jars[-1]) if jars else None


def org_json_jar():
    """org.json jar (build.gradle pins 20240303)."""
    found = _first_existing_env('ORG_JSON_JAR')
    if found:
        return found
    return _cache_jar('org.json', 'json', 'json')


def jsch_jar():
    """JSch jar (build.gradle pins com.github.mwiede:jsch:2.28.0)."""
    found = _first_existing_env('JSCH_JAR')
    if found:
        return found
    return _cache_jar('com.github.mwiede', 'jsch', 'jsch')


def android_jar():
    """android.jar for compiling Android-dependent sources."""
    found = _first_existing_env('ANDROID_JAR')
    if found:
        return found
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
    if not sdk.is_dir():
        return None
    platforms = sorted(sdk.glob('platforms/android-*/android.jar'))
    return str(platforms[-1]) if platforms else None


def require(jar, label):
    """Print an honest skip line and exit 0 when a jar is unavailable."""
    if jar:
        return jar
    print('SKIP: %s jar unavailable (set its env var or populate the Gradle cache)' % label)
    raise SystemExit(0)
