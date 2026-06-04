# Firmware publishing

Aplikacja Android pobiera manifest `firmware/latest.json` przez HTTPS. Manifest
musi wskazywac na gotowy plik `.bin`, a nie na zrodla Arduino.

Minimalne pola:

```json
{
  "device": "kejmil-oled-esp32",
  "name": "Kejmil OLED",
  "version": "1.0.1",
  "firmwareUrl": "https://raw.githubusercontent.com/Kamil14147/Update/main/firmware/releases/kejmil-oled-esp32-1.0.1.bin",
  "changelog": "Opis zmian",
  "sizeBytes": 1048576,
  "sha256": "64-znakowy-hash-sha256",
  "required": false
}
```

## Automatyczne budowanie na GitHubie

Workflow `.github/workflows/build-esp32-firmware.yml`:

1. instaluje Arduino CLI,
2. instaluje core `esp32:esp32`,
3. instaluje biblioteki Adafruit SSD1306, Adafruit GFX i ArduinoJson,
4. kompiluje `esp32/KejmilOLED/KejmilOLED.ino`,
5. kopiuje `.bin` do `firmware/releases/`,
6. generuje `firmware/latest.json` z rozmiarem i SHA-256,
7. commituje gotowy firmware do repo.

Po uruchomieniu workflow aplikacja moze pobrac manifest z:

```text
https://raw.githubusercontent.com/<user>/<repo>/main/firmware/latest.json
```

Ten adres ustaw w aplikacji na ekranie `Aktualizacja ESP32`.
