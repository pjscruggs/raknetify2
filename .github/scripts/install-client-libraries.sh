#!/usr/bin/env bash
set -euo pipefail

# The native client uses SDL/EGL on Xvfb with Mesa software rendering.
packages=(xvfb libgl1-mesa-dri libglx-mesa0 libegl1 libegl-mesa0 mesa-vulkan-drivers libasound2t64 libxkbcommon0)
install_packages() {
    sudo apt-get "$@" -o APT::Update::Error-Mode=any update &&
        sudo apt-get "$@" install -y "${packages[@]}"
}
if install_packages -o Acquire::Retries=0; then
    exit 0
fi

# Use the same Ubuntu suites and archive key if the runner's mirror is unavailable.
source /etc/os-release
sources=$(mktemp --suffix=.list)
trap 'rm -f "$sources"' EXIT
for suite in "$VERSION_CODENAME" "$VERSION_CODENAME-updates" "$VERSION_CODENAME-security"; do
    printf 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://mirrors.edge.kernel.org/ubuntu %s main universe\n' "$suite"
done > "$sources"
install_packages -o "Dir::Etc::sourcelist=$sources" -o Dir::Etc::sourceparts=-
