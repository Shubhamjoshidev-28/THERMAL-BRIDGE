# Thermal Bridge
Open this folder in Android Studio (it generates the Gradle wrapper), then Run / Build APK.

1. Pair the printer in Android Bluetooth settings.
2. Open app -> pick printer -> Save printer -> Start bridge -> Test print.
3. From a web page on the same device:

fetch("http://127.0.0.1:9101/print", {
  method: "POST",
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify({ text: "Hello\nTotal: 250\n", align: "left", bold: false, feed: 3, cut: true })
});

Raw ESC/POS instead of text: body { "raw": "<base64 bytes>" }
Health check: GET http://127.0.0.1:9101/status
