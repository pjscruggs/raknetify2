"""Select exactly one verified artifact for each role before starting the native client."""
import os
from pathlib import Path
import subprocess
import sys


def artifact(pattern, excluded=()):
    """Fail on ambiguous or missing build outputs instead of using an arbitrary JAR."""
    matches = [path for path in Path.cwd().glob(pattern) if not path.name.endswith(excluded)]
    if len(matches) != 1:
        raise ValueError('Expected one build artifact: ' + pattern)
    return str(matches[0])


def main():
    """Run one declared scenario on a fresh display with a bounded process lifetime."""
    temp = Path(os.environ['RUNNER_TEMP'])
    scenario = os.environ['SCENARIO']
    args = ['--jar', artifact('fabric-universal/build/libs/*.jar'), '--fixture', artifact('fabric-26/build/libs/*-integration-test.jar'),
            '--mode', os.environ['MODE'], '--work-dir', str(temp / 'integration'), '--assets-dir', str(temp / 'game-assets'),
            '--cache-dir', str(temp / 'integration-cache')]
    if scenario == 'paper-plugins':
        script = 'integration-paper.py'
        args.extend(['--plugin', artifact('spigot/build/libs/*.jar', ('-dev.jar', '-integration-test.jar')),
                     '--plugin-fixture', artifact('spigot/build/libs/*-integration-test.jar')])
    else:
        script = 'integration-smoke.py'
        args.extend(['--scenario', scenario, '--server-version', os.environ['SERVER'],
                     '--proxy-jar', artifact('velocity/build/libs/*-all.jar')])
    env = os.environ | {'LIBGL_ALWAYS_SOFTWARE': 'true', 'SDL_VIDEO_FORCE_EGL': '1', 'ALSOFT_DRIVERS': 'null'}
    command = ['xvfb-run', '-a', '-s', '-screen 0 1280x720x24', sys.executable, str(Path(__file__).with_name(script))]
    subprocess.run(command + args, env=env, check=True, timeout=1200)


if __name__ == '__main__':
    main()
