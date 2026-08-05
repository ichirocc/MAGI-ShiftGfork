#!/usr/bin/env bash
set -euo pipefail
MANIFEST="${1:-app/src/main/AndroidManifest.xml}"
if [[ ! -f "$MANIFEST" ]]; then
  echo "manifest not found: $MANIFEST" >&2
  exit 0
fi
if grep -q 'BubbleActivity' "$MANIFEST"; then
  echo "BubbleActivity already in manifest"
  exit 0
fi
python3 - "$MANIFEST" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
if "BubbleActivity" in text:
    print("already present")
    raise SystemExit(0)
snip = '''        <activity
            android:name=".work.BubbleActivity"
            android:exported="false"
            android:allowEmbedded="true"
            android:resizeableActivity="true"
            android:documentLaunchMode="always"
            android:theme="@style/Theme.Magi" />
'''
if "</application>" not in text:
    print("no application tag", file=sys.stderr)
    raise SystemExit(1)
text = text.replace("</application>", snip + "    </application>", 1)
open(path, "w", encoding="utf-8").write(text)
print("inserted BubbleActivity")
PY
