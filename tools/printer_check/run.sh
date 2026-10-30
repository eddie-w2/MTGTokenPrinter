#!/usr/bin/env bash
# Checks that the Kotlin printer drivers produce byte-for-byte the same jobs as TiMini-Print.
# Needs: a JDK, kotlinc (github.com/JetBrains/kotlin releases), git, python3.
# Usage: tools/printer_check/run.sh /path/to/kotlinc /path/to/TiMini-Print /path/to/JSON-java
set -euo pipefail
KOTLINC=$1; TIMINI=$2; JSONJAVA=$3
HERE=$(cd "$(dirname "$0")" && pwd); APP=$HERE/../../app/src/main
W=$(mktemp -d); mkdir -p "$W/src" "$W/jcls" "$W/out"
cp "$APP/java/com/example/tokenprinter/PrinterDrivers.kt" "$APP/java/com/example/tokenprinter/PrinterCatalog.kt" "$HERE/TestMain.kt" "$W/src/"
python3 - "$APP" "$W/src" <<'PY'
import re, sys
app, out = sys.argv[1], sys.argv[2]
l = open(app + '/java/com/example/tokenprinter/PrinterLink.kt').read()
head = l[:l.index('/** BLE link.')]
head = re.sub(r'^import android\..*\n', '', head, flags=re.M)
head = re.sub(r'^import kotlinx\.coroutines\.(withContext|withTimeout|Dispatchers|CompletableDeferred)\n', '', head, flags=re.M)
open(out + '/PrinterLink.kt', 'w').write(head)
c = open(app + '/java/com/example/tokenprinter/PrinterClient.kt').read()
a = c.index('/** What the printer last reported'); b = c.index('/**\n * Finds printers')
open(out + '/PrinterInfo.kt', 'w').write('package com.example.tokenprinter\n\n' + c[a:b])
PY
javac -d "$W/jcls" "$JSONJAVA"/src/main/java/org/json/*.java
"$KOTLINC" "$HERE/stubs" "$W/src" -cp "$W/jcls" -include-runtime -d "$W/test.jar"
java -cp "$W/test.jar:$W/jcls" com.example.tokenprinter.TestMainKt "$APP/assets" "$W/out"
(cd "$TIMINI" && PYTHONPATH="$HERE:." python3 "$HERE/compare.py" "$W/out")
