#!/usr/bin/env bash
# Hunspell dictionaries (npm "dictionary-*" packages by wooorm: unchanged upstream .aff/.dic files, each under its own
# open-source licence, copied next to the dictionary as <code>.license) -> app/src/main/assets/dict. Nothing is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/dict
mkdir -p "$OUT" engine/dict
LIST="bg:2.0.0 br:2.0.0 ca:3.0.0 cs:4.0.0 cy:2.0.0 da:6.0.0 de:3.0.0 de-at:3.0.0 de-ch:3.0.0 el:4.0.0 en:4.0.0 en-au:3.0.0
en-ca:3.0.0 en-gb:3.0.0 es:4.0.0 es-ar:2.0.0 es-mx:2.0.0 et:2.0.0 eu:4.0.0 fr:3.0.0 ga:3.0.0 gl:3.0.0 hr:2.0.0 hu:3.0.0 is:2.0.0
it:2.0.0 la:2.0.0 lb:4.0.0 lt:2.0.0 lv:2.0.0 mk:2.0.0 nb:3.0.0 nl:2.0.0 nn:3.0.0 pl:2.0.0 pt:4.0.0 pt-pt:2.0.0 ro:3.0.0 ru:3.0.0
sk:2.0.0 sl:3.0.0 sr:4.0.0 sr-latn:4.0.0 sv:4.0.0 tr:2.0.0 uk:4.0.0 vi:3.0.0"
for entry in $LIST; do
  code=${entry%%:*}; ver=${entry##*:}
  [ -f "$OUT/$code.dic" ] && continue
  tgz="engine/dict/$code-$ver.tgz"
  [ -f "$tgz" ] || curl -fL -o "$tgz" "https://registry.npmjs.org/dictionary-$code/-/dictionary-$code-$ver.tgz"
  tmp=$(mktemp -d)
  tar -xzf "$tgz" -C "$tmp"
  cp "$tmp/package/index.aff" "$OUT/$code.aff"
  cp "$tmp/package/index.dic" "$OUT/$code.dic"
  lic=$(ls "$tmp"/package | grep -i -m1 "^licen[cs]e" || true)
  [ -n "$lic" ] && cp "$tmp/package/$lic" "$OUT/$code.license"
  rm -rf "$tmp"
done
echo "dictionaries ready: $(ls "$OUT"/*.dic | wc -l)"
