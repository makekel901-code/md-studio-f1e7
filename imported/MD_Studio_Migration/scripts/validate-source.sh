#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
for path in root.rglob('*.xml'):
    ET.parse(path)
for path in root.rglob('*.json'):
    json.loads(path.read_text(encoding='utf-8'))
print('XML and JSON syntax: OK')
PY
for script in "$ROOT"/scripts/*.sh; do bash -n "$script"; done
printf 'Shell syntax: OK\n'
if command -v ruby >/dev/null 2>&1; then
  for workflow in "$ROOT"/.github/workflows/*.yml; do ruby -e 'require "yaml"; YAML.load_file(ARGV.fetch(0), aliases: true)' "$workflow"; done
  printf 'Workflow YAML parse: OK\n'
else
  printf 'Ruby YAML parser unavailable; skipped workflow YAML parse.\n'
fi
