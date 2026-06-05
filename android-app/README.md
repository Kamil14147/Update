# KESP32 Android

Aplikacja Android w Kotlinie. Dziala jako Foreground Service, sama laczy sie z
ESP32 `Kejmil OLED`, zbiera realne dane z telefonu i wysyla do ESP32 tylko
najwazniejszy widget. Tryb debug jest dodatkiem, nie glownym sposobem pracy.

## Budowanie APK

```powershell
cd C:\Users\krawc\Documents\ESP32\android-app
.\gradlew.bat assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Instalacja:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Uprawnienia

Aplikacja poprosi o:

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`;
- Android 11 i starsze: lokalizacja do skanowania BLE;
- lokalizacja: pogoda z Open-Meteo z ostatniej znanej pozycji;
- Android 13+: `POST_NOTIFICATIONS`;
- `READ_PHONE_STATE` i `READ_CALL_LOG` do stanu polaczen, jesli system pozwoli;
- `INTERNET` do pobierania pogody i firmware z GitHuba.

Recznie wlacz tez:

- dostep do powiadomien: Settings -> Notifications -> Notification access;
- ignorowanie optymalizacji baterii: przycisk `Nie usypiaj aplikacji`;
- Bluetooth.

Bez dostepu do powiadomien aplikacja nie odczyta powiadomien, nawigacji ani
pelnych MediaSession z aktywnych aplikacji.

## Realne zrodla danych

- Muzyka: `MediaSessionManager` i `MediaController`, fallback z powiadomien typu transport.
- Nawigacja: `NotificationListenerService` parsuje aktywne powiadomienia Google
  Maps, Waze, Yanosik i podobnych. Android nie udostepnia publicznego API z
  pelnymi instrukcjami turn-by-turn dla obcych aplikacji, wiec to najlepszy
  praktyczny wariant.
- Powiadomienia: kategorie message i popularne aplikacje, np. Messenger,
  WhatsApp, Discord, Signal, Telegram, SMS.
- Polaczenia: `TelephonyManager` oraz powiadomienia call. Numer moze byc ukryty.
- Bateria: `BatteryManager` i `ACTION_BATTERY_CHANGED`.
- Pogoda: Open-Meteo po HTTPS z ostatniej znanej lokalizacji, fallback z
  powiadomien pogodowych.

## Ekrany aplikacji

Aplikacja nazywa sie `KESP32` i ma minimalistyczny UI z dolnym paskiem
nawigacji, ikonami, animowanymi przejsciami oraz przelacznikiem jasny/ciemny.
Zakladka `Widgety` ma tez podglad OLED 128x64 renderowany z ostatniego JSON-a
wyslanego do ESP32.

Dolny pasek:

- `Status`: BLE, aktywny widget, uprawnienia i przyciski serwisowe;
- `Widgety`: wlaczanie/wylaczanie widgetow i priorytety;
- `Ustaw.`: motyw, autostart, uprawnienia, bateria i tryb debug;
- `Update`: firmware ESP32 oraz aktualizacja samej aplikacji;
- `Logi`: ostatni JSON i ostatnie zdarzenia.

Jesli aplikacja wykryje nowszy firmware, pokazuje okno z modelem 3D ESP32,
obecna wersja, nowa wersja, changelog, rozmiar pliku i przyciskiem `Aktualizuj`.
Po starcie aktualizacji przyciski `Pozniej` i `Aktualizuj` znikaja; zostaje
`Anuluj`, pasek postepu i przewidywany czas.
Model 3D jest wbudowany w APK jako `app/src/main/assets/esp32.stl`.

Obslugiwane widgety: polaczenie, nawigacja, powiadomienia, muzyka, bateria,
pogoda, WiFi, pamiec plikow, RAM, alarm, status telefonu i ekran glowny.

## OTA przez BLE

Adres manifestu ustawiasz w sekcji `Aktualizacja ESP32`. Domyslnie:

```text
https://raw.githubusercontent.com/Kamil14147/Update/main/firmware/latest.json
```

Manifest musi wskazywac gotowy `.bin`:

```json
{
  "device": "kejmil-oled-esp32",
  "name": "Kejmil OLED",
  "version": "1.0.3",
  "firmwareUrl": "https://raw.githubusercontent.com/Kamil14147/Update/main/firmware/releases/kejmil-oled-esp32-1.0.3.bin",
  "changelog": "Opis zmian",
  "sizeBytes": 123456,
  "sha256": "64 znaki SHA-256",
  "required": false
}
```

Przebieg:

1. aplikacja odczytuje wersje ESP32 przez BLE,
2. pobiera manifest z GitHuba,
3. porownuje wersje,
4. pobiera `.bin`,
5. sprawdza rozmiar i SHA-256,
6. wysyla OTA po BLE,
7. ESP32 restartuje sie,
8. aplikacja laczy sie ponownie i odczytuje wersje.

Aktualizacja nigdy nie startuje bez klikniecia `Aktualizuj`.

## Aktualizacja aplikacji KESP32

Aplikacja potrafi sprawdzac aktualizacje samej siebie podobnie jak firmware
ESP32:

1. pobiera `android-app/latest.json` po HTTPS,
2. porownuje `versionCode` z aktualnie zainstalowana aplikacja,
3. pokazuje popup, jesli jest nowsza wersja,
4. pobiera APK,
5. sprawdza rozmiar i SHA-256,
6. otwiera systemowy instalator Androida.

Android nie pozwala zwyklej aplikacji zainstalowac update'u po cichu. Uzytkownik
musi potwierdzic instalacje w systemowym oknie. Na Androidzie 8+ trzeba tez
zezwolic KESP32 na instalowanie nieznanych aplikacji.

Manifest aplikacji:

```json
{
  "packageName": "pl.kejmil.oledsender",
  "name": "KESP32",
  "versionName": "1.4",
  "versionCode": 5,
  "apkUrl": "https://raw.githubusercontent.com/Kamil14147/Update/main/android-app/releases/KESP32-1.4.apk",
  "changelog": "Opis zmian",
  "sizeBytes": 1234567,
  "sha256": "64 znaki SHA-256",
  "required": false
}
```

Wazne: APK musi byc podpisany tym samym kluczem co wersja juz zainstalowana na
telefonie. Jesli podpis jest inny, Android odmowi aktualizacji.

## Konfiguracja aktualizacji aplikacji na GitHubie

1. Wygeneruj release keystore lokalnie i zachowaj go bezpiecznie:

```powershell
keytool -genkeypair -v -keystore kesp32-release.jks -alias kesp32 `
  -keyalg RSA -keysize 2048 -validity 10000
```

2. Zakoduj keystore do base64 i dodaj jako sekret GitHuba
   `KESP32_KEYSTORE_BASE64`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("kesp32-release.jks"))
```

3. Dodaj tez sekrety:

```text
KESP32_KEYSTORE_PASSWORD
KESP32_KEY_ALIAS
KESP32_KEY_PASSWORD
```

Workflow akceptuje tez stare sekrety `KEPS32V1_*`, jesli juz byly ustawione.

4. Przy nowej wersji aplikacji zwieksz w `android-app/app/build.gradle`:

```gradle
versionCode 5
versionName "1.4"
```

5. Uruchom workflow `Build Android app`. Jesli sekrety podpisu sa ustawione,
   zbuduje podpisany APK, wrzuci go do `android-app/releases/` i odswiezy
   `android-app/latest.json`. Bez sekretow workflow zbuduje tylko debug APK jako
   artefakt testowy i nie opublikuje go jako bezpiecznego self-update.

Adres manifestu aplikacji w zakladce `Update`:

```text
https://raw.githubusercontent.com/Kamil14147/Update/main/android-app/latest.json
```

## Konfiguracja aktualizacji z GitHuba

1. Wgraj repo na GitHuba. Repo musi byc publiczne albo pliki manifestu i `.bin`
   musza byc dostepne po HTTPS bez logowania.
2. Domyslnie aplikacja wskazuje `Kamil14147/Update`. Jesli uzywasz innego repo,
   wpisz w zakladce `Update` swoj URL manifestu:

```text
https://raw.githubusercontent.com/<user>/<repo>/<branch>/firmware/latest.json
```

3. Przy nowej wersji zmien w `esp32/KejmilOLED/KejmilOLED.ino`:

```cpp
static const char *FW_VERSION = "1.0.3";
```

4. Scommituj i wypchnij zmiany na GitHuba.
5. W GitHub Actions uruchom workflow `Build ESP32 firmware`. Workflow kompiluje
   `.bin`, kopiuje go do `firmware/releases/` i aktualizuje `firmware/latest.json`
   z rozmiarem oraz SHA-256.
6. Aplikacja porowna wersje odczytana z ESP32 z `version` w manifiescie.
   Popup pojawi sie tylko wtedy, gdy wersja z GitHuba jest nowsza.

Do lokalnego testu po wgraniu ESP32 `1.0.2` zbuduj na GitHubie np. `1.0.3`.
Wtedy telefon odczyta z ESP32 `1.0.2`, pobierze manifest `1.0.3` i pokaze
okno aktualizacji z modelem 3D.

## Typowe problemy

- Brak BLE: wlacz Bluetooth i sprawdz, czy ESP32 reklamuje `Kejmil OLED`.
- Brak nawigacji/powiadomien: wlacz dostep do powiadomien dla aplikacji.
- Muzyka sie nie pojawia: odtwarzacz musi wystawiac MediaSession lub
  powiadomienie transportowe.
- Numer telefonu niedostepny: Android lub operator moze go blokowac mimo zgody.
- Pogoda brak: telefon nie ma ostatniej znanej lokalizacji albo lokalizacja jest
  zablokowana.
- Serwis znika w tle: wlacz ignorowanie optymalizacji baterii i ustawienia
  autostartu producenta telefonu.
- OTA nie startuje: manifest ma zly URL, `device`, rozmiar, SHA-256 albo ESP32
  ma starszy firmware bez serwisu OTA.
- Update aplikacji nie instaluje sie: APK jest podpisany innym kluczem albo
  Android nie ma wlaczonej zgody na instalowanie z KESP32.
