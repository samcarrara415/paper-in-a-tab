#!/bin/bash
# Rebuilds the whole Java-8 port pipeline after a jvmdg or patch change:
# tool -> api jar -> downgrade all 105 jars -> splice bytecode patches.
set -e
cd "$(dirname "$0")"
J25=$PWD/jdk-25.0.4.1+1/Contents/Home

echo "== building patched JvmDowngrader"
(cd jvmdg-src && JAVA_HOME=$J25 ./gradlew shadowJar -x test --console=plain -q > /dev/null)
cp jvmdg-src/build/libs/jvmdowngrader-2.0.1-all.jar jvmdg-patched.jar

echo "== downgrading api jar"
$J25/bin/java -jar jvmdg-patched.jar -c 52 downgrade \
  --target jvmdg-src/java-api/build/tmp/shadowJar/jvmdowngrader-java-api-2.0.1-all.jar api-52-all.jar 2>/dev/null

echo "== downgrading 105 server jars"
rm -rf dg
python3 - <<'EOF'
import os, subprocess, glob, sys
from concurrent.futures import ThreadPoolExecutor
jars = ['run25/versions/26.2/paper-26.2.jar'] + sorted(glob.glob('run25/libraries/**/*.jar', recursive=True))
cp = ':'.join(jars)
fails = []
def dg(j):
    out = 'dg/' + j.replace('run25/', '')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    r = subprocess.run(['./jdk-25.0.4.1+1/Contents/Home/bin/java', '-Xmx2g', '-jar', 'jvmdg-patched.jar',
                        '-c', '52', 'downgrade', '-cp', cp, '--target', j, out],
                       capture_output=True, text=True, timeout=1200)
    if r.returncode != 0 or not os.path.exists(out):
        fails.append(j)
with ThreadPoolExecutor(max_workers=6) as ex:
    list(ex.map(dg, jars))
if fails:
    print('FAILED:', fails); sys.exit(1)
print('all jars downgraded')
EOF

echo "== splicing configurate record patch"
(cd cfg-classes && jar uf ../dg/libraries/org/spongepowered/configurate-core/4.2.0/configurate-core-4.2.0.jar \
  org/spongepowered/configurate/objectmapping/ObjectFieldDiscoverer*.class)

echo "== rebuild complete"
