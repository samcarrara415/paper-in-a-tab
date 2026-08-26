#!/bin/bash
# Assembles docs/jars26/ for the site: flat-copies the downgraded jars, adds
# the jvmdg runtime, applies the browser-only ASM patches, and writes a
# manifest.json the page uses to build CheerpJ's classpath.
set -e
cd "$(dirname "$0")"
OUT=../docs/jars26
rm -rf "$OUT" && mkdir -p "$OUT"

cp dg/versions/26.2/paper-26.2.jar "$OUT/"
find dg/libraries -name '*.jar' -exec cp {} "$OUT/" \;
cp api-52-all.jar jvmdg-patched.jar shade-asm.jar launcher26/launcher26.jar "$OUT/"

(cd ../tools && javac -cp asm.jar Patcher26.java)
java -cp ../tools/asm.jar:../tools Patcher26 bind "$OUT/paper-26.2.jar"
java -cp ../tools/asm.jar:../tools Patcher26 log4j "$OUT/log4j-api-2.26.0.jar"
java -cp ../tools/asm.jar:../tools Patcher26 netty "$OUT/netty-common-4.2.15.Final.jar"

python3 - "$OUT" <<'EOF'
import json, os, sys
out = sys.argv[1]
jars = sorted(f for f in os.listdir(out) if f.endswith('.jar'))
# launcher first, then support runtime, then paper, then the rest
head = ['launcher26.jar', 'api-52-all.jar', 'jvmdg-patched.jar', 'shade-asm.jar', 'paper-26.2.jar']
ordered = head + [j for j in jars if j not in head]
sizes = {j: os.path.getsize(os.path.join(out, j)) for j in ordered}
with open(os.path.join(out, 'manifest.json'), 'w') as f:
    json.dump({'jars': ordered, 'sizes': sizes}, f)
print(len(ordered), 'jars,', sum(sizes.values()) // (1 << 20), 'MB total')
EOF
echo "browser jars ready in $OUT"
