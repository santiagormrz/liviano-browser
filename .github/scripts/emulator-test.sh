#!/usr/bin/env bash
# Prueba de humo en emulador: instala ABrowser, lo abre, toca el escudo de uBlock
# Origin y deja en el registro el logcat y capturas (JPEG en base64).
set -u
PKG=com.assboxer.abrowser

shot() {
  adb exec-out screencap -p > "/tmp/$1.png"
  python3 - "$1" <<'PY'
import base64, io, sys
from PIL import Image
name = sys.argv[1]
im = Image.open(f"/tmp/{name}.png").convert("RGB")
im.thumbnail((480, 1000))
buf = io.BytesIO(); im.save(buf, "JPEG", quality=70)
print(f"==CAPTURA {name}==" + base64.b64encode(buf.getvalue()).decode() + "==FIN==")
PY
}

tap_desc() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local b
  b=$(grep -o "content-desc=\"$1\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.xml | grep -o 'bounds="[^"]*"' | head -1)
  if [ -z "$b" ]; then echo "No encontré '$1' en pantalla"; return 1; fi
  read -r x1 y1 x2 y2 <<<"$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

APK=$(ls apk/*x86_64*.apk | head -1)
echo "Instalando $APK"
adb install -r "$APK"
adb logcat -c
adb shell am start -n "$PKG/ar.liviano.browser.MainActivity"
sleep 60
shot inicio

echo "Tocando el escudo de uBlock Origin"
tap_desc "uBlock Origin"
sleep 20
shot panel_ublock
adb shell input keyevent KEYCODE_BACK
sleep 3

echo "Abriendo una página con publicidad"
adb shell am start -a android.intent.action.VIEW -d "https://www.speedtest.net/" "$PKG"
sleep 40
shot pagina

echo "===== LOGCAT ====="
adb logcat -d | grep -iE "ABrowser|WebExtension|ublock|GeckoConsole|Extension|AndroidRuntime|FATAL" | tail -400
exit 0
