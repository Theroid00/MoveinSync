#!/usr/bin/env bash
# Checks everything this project needs and, with --install, installs what is missing.
#   scripts/setup.sh             check only; prints the exact install command for anything missing
#   scripts/setup.sh --install   install missing tools (Homebrew on macOS, apt or dnf on Linux)
# Running the system needs only Docker (with Compose), curl and jq. JDK 21 is for the test suite and
# Python 3 for the benchmarks. Portable: bash 3.2+ on macOS and Linux.
set -u
cd "$(dirname "$0")/.."
. scripts/jdk.sh

INSTALL=0
[ "${1:-}" = "--install" ] && INSTALL=1
OS=$(uname -s)
case "$OS" in
  MINGW*|MSYS*|CYGWIN*)
    echo "This looks like a Windows shell (Git Bash/MSYS). Use WSL instead:"
    echo "  1. In PowerShell: winget install -e --id Docker.DockerDesktop   and   wsl --install"
    echo "  2. In Docker Desktop: Settings > Resources > WSL integration > enable Ubuntu"
    echo "  3. Open the Ubuntu terminal, clone the repository there and run: scripts/setup.sh --install"
    exit 1 ;;
esac
WSL=0
grep -qi microsoft /proc/version 2>/dev/null && WSL=1
PM=none
if [ "$OS" = Darwin ]; then
  command -v brew >/dev/null 2>&1 && PM=brew
elif command -v apt-get >/dev/null 2>&1; then
  PM=apt
elif command -v dnf >/dev/null 2>&1; then
  PM=dnf
fi
SUDO=""
[ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
MISSING_REQUIRED=0
APT_UPDATED=0

ok()   { printf '  \342\234\223 %-14s %s\n' "$1" "$2"; }
bad()  { printf '  \342\234\227 %-14s %s\n' "$1" "$2"; }
note() { printf '      %s\n' "$*"; }

package_for() {
  case "$PM:$1" in
    brew:java) echo openjdk@21 ;;
    apt:java)  echo openjdk-21-jdk-headless ;;
    dnf:java)  echo java-21-openjdk-devel ;;
    *)         echo "$1" ;;
  esac
}

install_command() {
  case "$PM" in
    brew) echo "brew install $*" ;;
    apt)  echo "${SUDO:+$SUDO }apt-get install -y $*" ;;
    dnf)  echo "${SUDO:+$SUDO }dnf install -y $*" ;;
    *)    echo "install '$*' with your package manager" ;;
  esac
}

install_package() {
  [ "$PM" = none ] && return 1
  if [ "$PM" = apt ] && [ "$APT_UPDATED" = 0 ]; then
    $SUDO apt-get update -qq >/dev/null && APT_UPDATED=1
  fi
  note "installing: $(install_command "$@")"
  DEBIAN_FRONTEND=noninteractive eval "$(install_command "$@" | sed 's/apt-get /env DEBIAN_FRONTEND=noninteractive apt-get /')" >/dev/null 2>&1
}

# check_tool <name> <required:1|0> <purpose> <probe command>
check_tool() {
  local name=$1 required=$2 purpose=$3 probe=$4 version pkg
  if version=$(eval "$probe" 2>/dev/null) && [ -n "$version" ]; then
    ok "$name" "$version"
    return 0
  fi
  pkg=$(package_for "$name")
  if [ "$INSTALL" = 1 ] && install_package "$pkg" && version=$(eval "$probe" 2>/dev/null) && [ -n "$version" ]; then
    ok "$name" "$version (installed)"
    return 0
  fi
  bad "$name" "missing ($purpose)"
  note "$(install_command "$pkg")"
  [ "$required" = 1 ] && MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
  return 1
}

check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    if [ "$INSTALL" = 1 ] && [ "$PM" = brew ]; then
      note "installing Colima (a lightweight Docker runtime, no admin rights needed): brew install colima docker docker-compose docker-buildx"
      brew install colima docker docker-compose docker-buildx >/dev/null 2>&1
      mkdir -p "$HOME/.docker"
      [ -s "$HOME/.docker/config.json" ] || echo '{}' > "$HOME/.docker/config.json"
      if command -v jq >/dev/null 2>&1; then
        jq --arg d "$(brew --prefix)/lib/docker/cli-plugins" '.cliPluginsExtraDirs = ((.cliPluginsExtraDirs // []) + [$d] | unique)' \
          "$HOME/.docker/config.json" > "$HOME/.docker/config.json.tmp" && mv "$HOME/.docker/config.json.tmp" "$HOME/.docker/config.json"
      fi
    fi
  fi
  if ! command -v docker >/dev/null 2>&1; then
    bad docker "missing (required: runs the whole system)"
    if [ "$OS" = Darwin ]; then
      note "install Docker Desktop (https://www.docker.com/products/docker-desktop/) or run: scripts/setup.sh --install (installs Colima)"
    else
      if [ "$WSL" = 1 ]; then
        note "install Docker Desktop on Windows (winget install -e --id Docker.DockerDesktop) and enable WSL integration for this distribution"
      else
        note "install Docker Engine: https://docs.docker.com/engine/install/ then: sudo usermod -aG docker \$USER (log in again)"
      fi
    fi
    MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
    return
  fi
  if ! docker info >/dev/null 2>&1 && [ "$INSTALL" = 1 ] && command -v colima >/dev/null 2>&1; then
    note "starting Colima (4 CPU, 8 GB): colima start --cpu 4 --memory 8"
    colima start --cpu 4 --memory 8 >/dev/null 2>&1
  fi
  if docker info >/dev/null 2>&1; then
    ok docker "$(docker version --format '{{.Server.Version}}' 2>/dev/null) (daemon running)"
  else
    bad docker "installed, but the daemon is not running"
    if command -v colima >/dev/null 2>&1; then note "colima start --cpu 4 --memory 8"
    elif [ "$WSL" = 1 ]; then note "start Docker Desktop on Windows and enable WSL integration for this distribution"
    else note "start Docker Desktop (or: sudo systemctl start docker)"; fi
    MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
    return
  fi
  if docker compose version >/dev/null 2>&1; then
    ok compose "$(docker compose version --short 2>/dev/null)"
  else
    bad compose "missing (required: docker compose v2)"
    note "$(install_command "$( [ "$PM" = brew ] && echo docker-compose || echo docker-compose-plugin)")"
    MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
  fi
}

check_java() {
  local jdk
  if ! jdk=$(find_jdk21) && [ "$INSTALL" = 1 ]; then
    install_package "$(package_for java)"
    jdk=$(find_jdk21)
  fi
  if [ -n "${jdk:-}" ]; then
    if command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -qE 'javac 21(\.|$)'; then
      ok "jdk 21" "$jdk"
    else
      ok "jdk 21" "$jdk (not on PATH; scripts/test.sh and make test select it automatically)"
    fi
  else
    bad "jdk 21" "missing (only for the test suite: make test)"
    note "$(install_command "$(package_for java)")"
  fi
}

check_port() {
  if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then
    bad "port $1" "in use: stop whatever listens on it (or this project: make down)"
    MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
  else
    ok "port $1" "free"
  fi
}

echo "Needed to run the system:"
check_docker
check_tool curl 1 "used by the scripts" "curl --version | head -1 | cut -d' ' -f1-2"
check_tool jq 1 "used by the scripts" "jq --version"
if docker compose ps -q roster 2>/dev/null | grep -q .; then
  ok "ports" "8080/8081 used by this project's running stack"
else
  check_port 8080
  check_port 8081
fi
echo
echo "Optional (tests, benchmarks, make shortcuts):"
check_java
check_tool python3 0 "benchmark report" "python3 --version"
check_tool make 0 "only for the shortcuts: make up / tour / crash" "make --version | head -1"
echo
if [ "$MISSING_REQUIRED" -eq 0 ]; then
  echo "Ready. Next: docker compose up -d --build --wait   (or: make up)"
  exit 0
fi
echo "$MISSING_REQUIRED required item(s) missing.$( [ "$INSTALL" = 0 ] && [ "$PM" != none ] && echo " Run: scripts/setup.sh --install")"
exit 1
