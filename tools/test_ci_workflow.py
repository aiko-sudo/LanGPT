#!/usr/bin/env python3
"""Exercise the production SDK-install shell with isolated CLI fixtures.

This checks shell failure propagation, not Google SDK downloads or GitHub CI.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
workflow = (ROOT / '.github/workflows/build.yml').read_text()
start = workflow.index('      - name: Install SDK packages')
end = workflow.index('\n      - ', start + 1)
step = workflow[start:end]
run_start = step.index('        run: |\n') + len('        run: |\n')
script = '\n'.join(line[10:] for line in step[run_start:].splitlines()) + '\n'
subprocess.run(['bash', '-n'], input=script, text=True, check=True)

with tempfile.TemporaryDirectory(prefix='langpt-ci-shell-', ignore_cleanup_errors=True) as temporary:
    root = Path(temporary)
    manager = root / 'cmdline-tools/latest/bin/sdkmanager'
    manager.parent.mkdir(parents=True)
    manager.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$SDK_TEST_ARGS"\nexit "${SDK_TEST_EXIT:-0}"\n')
    manager.chmod(0o755)
    arguments = root / 'args.txt'
    env = dict(os.environ, ANDROID_HOME=str(root), SDK_TEST_ARGS=str(arguments))
    for exit_code in (0, 42):
        result = subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script],
                                env=dict(env, SDK_TEST_EXIT=str(exit_code)), capture_output=True, text=True)
        assert result.returncode == exit_code, (exit_code, result.returncode, result.stderr)
        assert arguments.read_text().splitlines() == ['--install', 'platforms;android-36', 'build-tools;35.0.0']
        print('PASS SDK exit', exit_code, 'propagates under Actions pipefail')
    result = subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script],
                            env=dict(env, ANDROID_HOME=str(root / 'missing')), capture_output=True, text=True)
    assert result.returncode != 0, 'missing SDK must fail instead of silently succeeding'
    print('PASS missing SDK fails closed')
print('CI shell fixtures passed; hosted build and SDK download require an actual Actions run.')
