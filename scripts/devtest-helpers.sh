# Device test helpers for MatNotes (source this file; needs adb + a connected phone with the debug build installed).
# Always check the foreground app first (`guard`/`chk`): the phone is the owner's own phone.
#   newtable           new document + 3x3 table, caret in a cell
#   tbl                prints the caret table's borders (twips) from the debug dump
#   key <awtcode>      press a key (Alt+Right = 17411), unoa <cmd> <json> = uno command, shotc <name> "(l,t,r,b)" = cropped screenshot
S=${S:-/tmp/matnotes-devtest}; mkdir -p "$S"
chk(){ adb shell dumpsys activity activities | grep topResumedActivity | grep -q com.matnotes.app.debug || { echo NOT_MATNOTES; exit 1; }; }
guard(){ adb shell dumpsys activity activities | grep topResumedActivity | grep -q -e launcher -e matnotes || { echo FOREIGN; exit 1; }; }
dumpline(){ adb logcat -c; adb shell am start -n com.matnotes.app.debug/com.matnotes.app.MainActivity --ez dump true >/dev/null 2>&1; sleep 0.6; adb logcat -d -s MatNotesDump | tail -1 | cut -d: -f4- ; }
tbl(){ dumpline | sed 's/.*table=\([^ ]* [^ ]*\) markers.*/\1/' | sed 's/\.0//g'; }
key(){ adb shell am start -n com.matnotes.app.debug/com.matnotes.app.MainActivity --ei key $1 >/dev/null 2>&1; sleep 0.8; }
unoa(){ adb shell "am start -n com.matnotes.app.debug/com.matnotes.app.MainActivity --es uno $1 --es unoargs '$2'" >/dev/null 2>&1; }
newdoc(){ guard; adb shell am force-stop com.matnotes.app.debug; sleep 1; adb shell am start -n com.matnotes.app.debug/com.matnotes.app.MainActivity >/dev/null; sleep 3; chk; adb shell input tap 289 624; sleep 4; chk; }
newtable(){ newdoc; unoa .uno:InsertTable '{"Columns":{"type":"long","value":"3"},"Rows":{"type":"long","value":"3"}}'; sleep 3; chk; adb shell input tap 540 440; sleep 1.5; chk; }
shotc(){ adb exec-out screencap -p | python3 -c "
import sys,io
from PIL import Image
Image.open(io.BytesIO(sys.stdin.buffer.read())).crop($2).save('$S/$1.png')"; }
