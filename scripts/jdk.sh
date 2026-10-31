#!/usr/bin/env bash
# find_jdk21: prints the home of a JDK 21 (with javac), looking at JAVA_HOME, PATH, the macOS registry,
# Homebrew and the usual Linux locations. Returns non-zero when none is found. Sourced, not executed.

jdk_is_21() {
  [ -x "$1/bin/javac" ] && "$1/bin/javac" -version 2>&1 | grep -qE 'javac 21(\.|$)'
}

find_jdk21() {
  local candidate brew_prefix javac_path link
  # javac on PATH, following symlinks (Linux alternatives). The macOS /usr/bin stub is skipped: without a
  # JDK it would pop up an "install Java" dialog.
  javac_path=$(command -v javac 2>/dev/null || true)
  if [ -n "$javac_path" ] && ! { [ "$(uname -s)" = Darwin ] && [ "$javac_path" = /usr/bin/javac ]; }; then
    while [ -L "$javac_path" ]; do
      link=$(readlink "$javac_path")
      case "$link" in /*) javac_path=$link ;; *) javac_path="$(dirname "$javac_path")/$link" ;; esac
    done
    candidate=$(dirname "$(dirname "$javac_path")")
    jdk_is_21 "$candidate" && { echo "$candidate"; return 0; }
  fi
  for candidate in "${JAVA_HOME:-}" "$( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 2>/dev/null)"; do
    [ -n "$candidate" ] && jdk_is_21 "$candidate" && { echo "$candidate"; return 0; }
  done
  if command -v brew >/dev/null 2>&1; then
    brew_prefix=$(brew --prefix 2>/dev/null)
    for candidate in "$brew_prefix/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" "$brew_prefix/opt/openjdk@21"; do
      jdk_is_21 "$candidate" && { echo "$candidate"; return 0; }
    done
  fi
  for candidate in /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/temurin-21* /usr/lib/jvm/jdk-21* "$HOME"/.sdkman/candidates/java/21*; do
    [ -d "$candidate" ] && jdk_is_21 "$candidate" && { echo "$candidate"; return 0; }
  done
  return 1
}
