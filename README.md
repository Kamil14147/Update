# KESP32 / Kejmil OLED

Kompletny projekt inteligentnego ekranika na ESP32 z OLED 0.96" 128x64.
Telefon z Androidem (`KESP32`) zbiera realne dane z systemu, wybiera najwazniejszy widget
i wysyla go przez BLE do ESP32 `Kejmil OLED`. ESP32 nie udaje danych telefonu:
tylko odbiera komunikaty, renderuje OLED i obsluguje firmware OTA przez BLE.

## Co jest w repo

```text
android-app/                  aplikacja Android/Kotlin
android-app/latest.json       manifest aktualizacji aplikacji
android-app/releases/         miejsce na gotowe pliki APK
esp32/KejmilOLED/             szkic Arduino dla ESP32
firmware/latest.json          manifest najnowszego firmware dla aplikacji
firmware/releases/            miejsce na gotowe pliki .bin z GitHuba
tools/write_firmware_manifest.py
tools/write_android_app_manifest.py
.github/workflows/build-esp32-firmware.yml
.github/workflows/build-android-app.yml
```

## Sprzet i OLED

OLED SSD1306 I2C 0.96" 128x64:

| OLED | ESP32 |
| ---- | ----- |
| GND  | GND   |
| VCC  | 3.3V  |
| SCL  | GPIO 22 |
| SDA  | GPIO 21 |

ESP32 reklamuje BLE jako `Kejmil OLED`.

## Realne dane z telefonu

Aplikacja Android dziala jako Foreground Service i sama laczy sie z ESP32.
Zrodla danych:

- muzyka: `MediaSessionManager` i `MediaController`;
- nawigacja: realne powiadomienia Google Maps, Waze, Yanosik i podobnych;
- powiadomienia: `NotificationListenerService`, z filtrowaniem waznych aplikacji;
- polaczenia: `TelephonyManager` plus powiadomienia kategorii call;
- bateria telefonu: `BatteryManager`;
- pogoda: Open-Meteo z ostatniej znanej lokalizacji oraz fallback z powiadomien pogodowych.

Ograniczenia Androida sa uczciwe: nawigacja nie ma publicznego, pelnego API
turn-by-turn dla obcych aplikacji, wiec najlepszy wariant to parsowanie
aktywnych powiadomien nawigacji. Numery rozmowcow tez moga byc ukryte przez
wersje Androida, operatora albo ustawienia prywatnosci.

## Priorytety widgetow

Domyslnie:

```text
100 polaczenie
90  nawigacja
80  wazne powiadomienie
70  muzyka
60  niska bateria
50  pogoda
10  ekran glowny
```

`WidgetManager` trzyma aktywne zdarzenia, usuwa wygasle wpisy, ma cooldown
przelaczania i pamieta poprzedni wazny widget. Zwykle powiadomienia maja timeout,
a po rozmowie ekran wraca np. do nawigacji.

## Firmware OTA przez aplikacje

Aktualizacja nie wysyla zrodel. GitHub buduje gotowy `.bin`, generuje manifest
z wersja, rozmiarem i SHA-256, a aplikacja Android:

1. odczytuje wersje firmware z ESP32,
2. pobiera `firmware/latest.json` z GitHuba,
3. porownuje wersje,
4. pobiera gotowy plik `.bin`,
5. sprawdza rozmiar i SHA-256,
6. wysyla firmware przez BLE OTA,
7. czeka na restart ESP32 i ponownie odczytuje wersje.

ESP32 podczas OTA pokazuje `Firmware update`, procent, `Do not disconnect`,
a po sukcesie `Update OK, rebooting`.

Aplikacja ma minimalistyczny UI z dolnym paskiem, ikonami, animowanymi
przejsciami i jasnym/ciemnym motywem. W zakladce `Update` obsluguje osobno
firmware ESP32 oraz aktualizacje samej aplikacji przez APK z GitHuba. Gdy
wykryje nowszy firmware ESP32, pokazuje okno z modelem 3D ESP32, obecna i nowa
wersja, changelogiem oraz przyciskiem `Aktualizuj`.

Aktualizacja aplikacji pobiera manifest `android-app/latest.json`, sprawdza
rozmiar i SHA-256 APK, a potem otwiera systemowy instalator Androida. APK musi
byc podpisany tym samym kluczem co poprzednia wersja.

## Szybki start

1. Wgraj `esp32/KejmilOLED/KejmilOLED.ino` do ESP32 w Arduino IDE.
2. Zbuduj APK:

```powershell
cd C:\Users\krawc\Documents\ESP32\android-app
.\gradlew.bat assembleDebug
```

3. Zainstaluj APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. W aplikacji wlacz uprawnienia Bluetooth, powiadomienia, dostep do powiadomien,
telefon i lokalizacje. Wlacz tez ignorowanie optymalizacji baterii.
5. Uruchom ESP32. Aplikacja znajdzie `Kejmil OLED` i zacznie wysylac realne dane.

Szczegoly sa w:

- `esp32/KejmilOLED/README.md`
- `android-app/README.md`
- `firmware/README.md`
