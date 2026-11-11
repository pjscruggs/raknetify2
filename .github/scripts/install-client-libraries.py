"""Install the display, audio and input libraries used by native Linux client tests."""
import platform
import subprocess
import tempfile


PACKAGES = ('xvfb', 'libgl1-mesa-dri', 'libglx-mesa0', 'libegl1', 'libegl-mesa0',
            'mesa-vulkan-drivers', 'libasound2t64', 'libxkbcommon0')


def install(*options):
    """Require current signed indexes before installing the client dependencies."""
    apt = ['sudo', 'apt-get', *options]
    subprocess.run([*apt, '-o', 'APT::Update::Error-Mode=any', 'update'], check=True)
    subprocess.run([*apt, 'install', '-y', *PACKAGES], check=True)


def main():
    """Try the runner mirror, then the same Ubuntu suites on an independent mirror."""
    try:
        install('-o', 'Acquire::Retries=0')
        return
    except subprocess.CalledProcessError:
        print('Retrying client library installation using the kernel.org Ubuntu mirror.', flush=True)
    codename = platform.freedesktop_os_release()['VERSION_CODENAME']
    with tempfile.NamedTemporaryFile(mode='w', encoding='ascii', suffix='.list') as sources:
        for suite in (codename, codename + '-updates', codename + '-security'):
            sources.write('deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] '
                          f'https://mirrors.edge.kernel.org/ubuntu {suite} main universe\n')
        sources.flush()
        install('-o', 'Dir::Etc::sourcelist=' + sources.name, '-o', 'Dir::Etc::sourceparts=-')


if __name__ == '__main__':
    main()
