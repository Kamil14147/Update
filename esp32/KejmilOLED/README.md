# KejmilOLED ESP32

Szkic Arduino dla ESP32 z OLED SSD1306 128x64 I2C. Urzadzenie reklamuje sie
przez BLE jako `Kejmil OLED`, odbiera realne dane z aplikacji Android i renderuje
widgety. Ma tez bezpieczny OTA przez BLE.

## Podlaczenie OLED

| OLED | ESP32 DevKit |
| ---- | ------------ |
| GND  | GND          |
| VCC  | 3.3V         |
| SDA  | GPIO 21      |
| SCL  | GPIO 22      |

Opcjonalny przycisk zmiany widgetu:

| Przycisk | ESP32 |
| -------- | ----- |
| jedna nozka | GPIO 25 |
| druga nozka | GND |

W kodzie uzywany jest `INPUT_PULLUP`.

## Biblioteki Arduino

W Arduino IDE zainstaluj:

- `Adafruit GFX Library`
- `Adafruit SSD1306`
- `ArduinoJson`
- pakiet plytek `esp32 by Espressif Systems`

Wybierz typ plytki np. `ESP32 Dev Module`.

## Wersja firmware

Wersja jest w pliku `.ino`:

```cpp
static const char *FW_VERSION = "1.0.0";
```

Aplikacja Android odczytuje ja przez BLE i porownuje z manifestem GitHuba.

## BLE UART dla widgetow

Serwis zgodny z Nordic UART:

```text
Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
RX:      6e400002-b5a3-f393-e0a9-e50e24dcca9e
TX:      6e400003-b5a3-f393-e0a9-e50e24dcca9e
```

Kazdy JSON konczy sie `\n`. ESP32 obsluguje:

```json
{"type":"home","time":"18:42","phoneBattery":82,"charging":true}
{"type":"music","title":"Song","artist":"Artist","state":"playing","progress":45}
{"type":"nav","instruction":"Turn right","distance":"300 m","street":"Main St","direction":"right"}
{"type":"notification","app":"Messenger","title":"Jan","text":"Hej","timeout":5000}
{"type":"call","name":"Mama","number":"+48123123123","state":"incoming"}
{"type":"battery","percent":18,"charging":false}
{"type":"weather","temp":"21 C","desc":"deszcz","city":"Krakow"}
```

ESP32 nie wybiera priorytetow. Priorytety liczy aplikacja Android i wysyla juz
wybrany widget.

## BLE OTA dla firmware

Dodatkowy serwis:

```text
Service: f00d0001-8b7a-4d2a-9c2f-4f4553503332
Control write/notify: f00d0002-8b7a-4d2a-9c2f-4f4553503332
Data write:           f00d0003-8b7a-4d2a-9c2f-4f4553503332
```

Protokol:

1. Android wysyla na control:

```json
{"cmd":"begin","device":"kejmil-oled-esp32","version":"1.0.1","size":123456,"sha256":"..."}
```

2. ESP32 sprawdza urzadzenie, rozmiar i miejsce OTA, uruchamia `Update.begin`
   i odpowiada `{"event":"ota","state":"ready"}`.
3. Android wysyla binarne fragmenty `.bin` na data.
4. ESP32 zapisuje flash, liczy SHA-256 i pokazuje postep na OLED.
5. Android wysyla `{"cmd":"finish"}`.
6. ESP32 sprawdza rozmiar oraz SHA-256, konczy `Update.end(true)` i restartuje sie.

Komendy pomocnicze:

```json
{"cmd":"version"}
{"cmd":"abort"}
```

## OLED

Ekrany:

- start: `Kejmil OLED` i pasek ladowania;
- brak telefonu: `Waiting for phone...`;
- polaczenie: `Phone connected`;
- zerwanie: `Disconnected`;
- home: godzina, bateria telefonu i `Powered by Kejmil`;
- muzyka, nawigacja, powiadomienie, polaczenie, bateria, pogoda;
- OTA: `Firmware update`, procent, `Do not disconnect`, sukces lub blad.

Za dlugie teksty sa ucinane lub przewijane. Status bar na dole pokazuje BLE,
baterie telefonu i `Kejmil`.

## Typowe problemy

- OLED pusty: sprawdz GND/VCC/SDA/SCL i adres `0x3C` albo `0x3D`.
- Telefon nie widzi ESP32: Serial Monitor 115200 powinien pokazac
  `[BLE] Advertising as Kejmil OLED`.
- BLE laczy sie, ale brak danych: sprawdz uprawnienia aplikacji Android i dostep
  do powiadomien.
- OTA odrzuca plik: manifest ma zly `device`, rozmiar, SHA-256 albo plik jest za duzy.
- OTA przerwane: aplikacja pokaze blad, ESP32 anuluje `Update.abort()`, a stary
  firmware zostaje aktywny.
- Krzaki w polskich znakach: OLED biblioteki Adafruit nie ma pelnego fontu PL,
  wiec firmware zamienia polskie znaki na ASCII.

