#!/usr/bin/env bash
# Verifies that every required test class (scripts/required-tests.txt) actually ran:
# tests > 0, failures = 0, errors = 0, skipped = 0, summed over all Surefire/Failsafe
# reports of that simple class name (a class may exist in more than one module).
# Exits non-zero and lists the gaps otherwise. Portable: bash 3.2+, grep, sed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REQUIRED="$ROOT/scripts/required-tests.txt"

reports=()
while IFS= read -r f; do reports+=("$f"); done < <(
  find "$ROOT" -path '*/target/surefire-reports/TEST-*.xml' -o -path '*/target/failsafe-reports/TEST-*.xml' 2>/dev/null | sort)

if [ "${#reports[@]}" -eq 0 ]; then
  echo "check-tests: no Surefire/Failsafe reports found; run ./mvnw verify first" >&2
  exit 1
fi

# attr <file> <name>: integer attribute of the root <testsuite> element
attr() {
  local v
  v=$(grep -m1 -o '<testsuite [^>]*' "$1" | grep -o " $2=\"[0-9]*\"" | head -1 | sed -E 's/.*"([0-9]*)"/\1/')
  echo "${v:-0}"
}

missing=0
checked=0
printf '%-40s %6s %6s %6s %6s  %s\n' CLASS TESTS FAIL ERR SKIP STATUS
while IFS= read -r line || [ -n "$line" ]; do
  cls="$(echo "$line" | sed -e 's/#.*//' -e 's/[[:space:]]//g')"
  [ -z "$cls" ] && continue
  checked=$((checked + 1))
  t=0; fl=0; er=0; sk=0; found=0
  for f in "${reports[@]}"; do
    base="$(basename "$f" .xml)"          # TEST-com.x.y.ClassName
    case "$base" in
      *."$cls") ;;
      *) continue ;;
    esac
    found=1
    t=$((t + $(attr "$f" tests)))
    fl=$((fl + $(attr "$f" failures)))
    er=$((er + $(attr "$f" errors)))
    sk=$((sk + $(attr "$f" skipped)))
  done
  status=OK
  if [ "$found" -eq 0 ]; then status=MISSING
  elif [ "$t" -eq 0 ]; then status=NO_TESTS
  elif [ "$fl" -ne 0 ] || [ "$er" -ne 0 ]; then status=FAILED
  elif [ "$sk" -ne 0 ]; then status=SKIPPED
  fi
  [ "$status" != OK ] && missing=$((missing + 1))
  printf '%-40s %6s %6s %6s %6s  %s\n' "$cls" "$t" "$fl" "$er" "$sk" "$status"
done < "$REQUIRED"

echo "check-tests: $checked required classes, $missing with problems"
[ "$missing" -eq 0 ]
