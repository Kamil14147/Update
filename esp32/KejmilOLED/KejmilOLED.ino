#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Update.h>
#include "mbedtls/sha256.h"
#include "mbedtls/version.h"

// OLED wiring for ESP32 DevKit.
static const uint8_t OLED_SDA = 21;
static const uint8_t OLED_SCL = 22;
static const uint8_t SCREEN_WIDTH = 128;
static const uint8_t SCREEN_HEIGHT = 64;
static const int8_t OLED_RESET = -1;
static const uint8_t OLED_ADDR_PRIMARY = 0x3C;
static const uint8_t OLED_ADDR_SECONDARY = 0x3D;

// Use GPIO25 for a safe external button. Change to 0 if you want to use BOOT.
static const uint8_t BUTTON_PIN = 25;
static const bool BUTTON_ENABLED = true;

static const char *DEVICE_ID = "kejmil-oled-esp32";
static const char *BLE_DEVICE_NAME = "Kejmil OLED";
static const char *FW_VERSION = "1.0.1";
static const char *UART_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *UART_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char *UART_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
static const char *OTA_SERVICE_UUID = "f00d0001-8b7a-4d2a-9c2f-4f4553503332";
static const char *OTA_CONTROL_UUID = "f00d0002-8b7a-4d2a-9c2f-4f4553503332";
static const char *OTA_DATA_UUID = "f00d0003-8b7a-4d2a-9c2f-4f4553503332";

static const uint16_t MAX_JSON_LENGTH = 768;
static const uint16_t MAX_OTA_CONTROL_LENGTH = 384;
static const uint16_t JSON_DOC_SIZE = 1536;
static const uint16_t DISPLAY_REFRESH_MS = 160;
static const uint16_t SCROLL_STEP_MS = 250;
static const uint16_t OTA_NOTIFY_INTERVAL_MS = 1000;

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

BLEServer *bleServer = nullptr;
BLECharacteristic *txCharacteristic = nullptr;
BLECharacteristic *otaControlCharacteristic = nullptr;

enum Screen {
  SCREEN_HOME,
  SCREEN_MUSIC,
  SCREEN_NAV,
  SCREEN_NOTIFICATION,
  SCREEN_CALL,
  SCREEN_BATTERY,
  SCREEN_WEATHER
};

bool oledReady = false;
bool bleConnected = false;
bool restartAdvertising = false;
String rxLine;
String otaControlLine;

Screen currentScreen = SCREEN_HOME;
unsigned long screenUntil = 0;
String noticeText;
unsigned long noticeUntil = 0;
unsigned long lastDrawAt = 0;

String phoneTime = "";
int phoneBattery = -1;
bool phoneCharging = false;

String musicTitle = "";
String musicArtist = "";
String musicState = "paused";
int musicProgress = -1;

String navInstruction = "";
String navDistance = "";
String navStreet = "";
String navDirection = "";

String notificationApp = "";
String notificationTitle = "";
String notificationText = "";

String callName = "";
String callNumber = "";
String callState = "";

String weatherTemp = "";
String weatherDesc = "";
String weatherCity = "";

bool lastButtonReading = HIGH;
bool buttonPressed = false;
unsigned long lastButtonChange = 0;

bool otaActive = false;
bool otaRebootPending = false;
unsigned long otaRebootAt = 0;
unsigned long otaDisplayUntil = 0;
unsigned long lastOtaNotifyAt = 0;
int otaDisplayProgress = -1;
int lastOtaProgress = -1;
size_t otaExpectedSize = 0;
size_t otaReceivedSize = 0;
String otaTargetVersion;
String otaExpectedSha256;
String otaDisplayState = "";
mbedtls_sha256_context otaShaContext;

String normalizeText(const String &input);
template <typename TDoc>
String field(TDoc &doc, const char *name, const char *fallback = "");
void processJsonLine(const String &line);
void handleIncomingBytes(const uint8_t *data, size_t len);
void handleOtaControlBytes(const uint8_t *data, size_t len);
void processOtaControlLine(const String &line);
void handleOtaData(const uint8_t *data, size_t len);
void sendBleLine(const String &line);
void sendVersionInfo();
void sendOtaLine(const String &line);
void sendOtaStatus(const String &state, const String &message = "");
bool beginOtaUpdate(const String &version, size_t size, const String &sha256);
void finishOtaUpdate();
void abortOtaUpdate(const String &reason);
String sha256Hex(const uint8_t *hash, size_t len);
void sha256Start(mbedtls_sha256_context *ctx);
void sha256Update(mbedtls_sha256_context *ctx, const uint8_t *data, size_t len);
void sha256Finish(mbedtls_sha256_context *ctx, uint8_t hash[32]);
void showNotice(const String &text, uint16_t durationMs);
void setScreen(Screen screen, uint16_t durationMs = 0);
Screen defaultScreen();
void cycleScreen();
void handleButton();
void updateAutoScreen();
void draw();
void drawStartup();
void drawNotice(const String &text);
void drawWaiting();
void drawHeader(const String &title);
void drawStatusBar();
void drawHome();
void drawMusic();
void drawNavigation();
void drawNotification();
void drawCall();
void drawBattery();
void drawWeather();
void drawFirmwareUpdate();
void drawBatteryIcon(int x, int y, int percent, bool charging);
void drawProgressBar(int x, int y, int w, int h, int percent);
void drawNavArrow(const String &direction, int x, int y);
String inferDirectionFromText(const String &text);
void drawScrollingLine(const String &text, int x, int y, int width);
void drawCenteredText(const String &text, int y, uint8_t textSize, uint8_t maxChars);
int centeredX(const String &text, uint8_t textSize);
void printFit(const String &text, uint8_t maxChars);
String fitText(const String &text, uint8_t maxChars);
String uptimeText();
bool timerActive(unsigned long until);

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    restartAdvertising = false;
    Serial.println("[BLE] Telefon polaczony");
    showNotice("Phone connected", 1400);
    sendVersionInfo();
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    restartAdvertising = true;
    Serial.println("[BLE] Telefon rozlaczony");
    if (otaActive) {
      abortOtaUpdate("ble_disconnected");
    }
    showNotice("Disconnected", 1600);
  }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();
    if (value.length() == 0) {
      return;
    }

    handleIncomingBytes(reinterpret_cast<const uint8_t *>(value.c_str()), value.length());
  }
};

class OtaControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();
    if (value.length() == 0) {
      return;
    }
    handleOtaControlBytes(reinterpret_cast<const uint8_t *>(value.c_str()), value.length());
  }
};

class OtaDataCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();
    if (value.length() == 0) {
      return;
    }
    handleOtaData(reinterpret_cast<const uint8_t *>(value.c_str()), value.length());
  }
};

void setup() {
  Serial.begin(115200);
  delay(250);
  Serial.println();
  Serial.println("Kejmil OLED ESP32 starting...");
  Serial.print("Firmware version: ");
  Serial.println(FW_VERSION);

  if (BUTTON_ENABLED) {
    pinMode(BUTTON_PIN, INPUT_PULLUP);
  }

  Wire.begin(OLED_SDA, OLED_SCL);
  oledReady = display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR_PRIMARY);
  if (!oledReady) {
    Serial.println("[OLED] Address 0x3C failed, trying 0x3D...");
    oledReady = display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR_SECONDARY);
  }

  if (oledReady) {
    display.clearDisplay();
    display.setTextColor(SSD1306_WHITE);
    drawStartup();
  } else {
    Serial.println("[OLED] Display not found. Check wiring and I2C address.");
  }

  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(517);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *uartService = bleServer->createService(UART_SERVICE_UUID);

  txCharacteristic = uartService->createCharacteristic(
    UART_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  txCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *rxCharacteristic = uartService->createCharacteristic(
    UART_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  rxCharacteristic->setCallbacks(new RxCallbacks());

  uartService->start();

  BLEService *otaService = bleServer->createService(OTA_SERVICE_UUID);

  otaControlCharacteristic = otaService->createCharacteristic(
    OTA_CONTROL_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY
  );
  otaControlCharacteristic->addDescriptor(new BLE2902());
  otaControlCharacteristic->setCallbacks(new OtaControlCallbacks());

  BLECharacteristic *otaDataCharacteristic = otaService->createCharacteristic(
    OTA_DATA_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  otaDataCharacteristic->setCallbacks(new OtaDataCallbacks());

  otaService->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(UART_SERVICE_UUID);
  advertising->addServiceUUID(OTA_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] Advertising as Kejmil OLED");
  showNotice("Waiting for phone", 1300);
}

void loop() {
  if (otaRebootPending && static_cast<long>(millis() - otaRebootAt) >= 0) {
    ESP.restart();
  }

  if (restartAdvertising) {
    restartAdvertising = false;
    delay(100);
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Advertising restarted");
  }

  handleButton();
  updateAutoScreen();
  draw();
}

String normalizeText(const String &input) {
  String output;
  output.reserve(input.length());

  for (int i = 0; i < input.length();) {
    uint8_t c = static_cast<uint8_t>(input[i]);
    if (c < 128) {
      output += static_cast<char>(c);
      i++;
      continue;
    }

    if (i + 1 >= input.length()) {
      output += '?';
      i++;
      continue;
    }

    uint8_t n = static_cast<uint8_t>(input[i + 1]);

    if (c == 0xC3 && n == 0xB3) output += 'o';       // o acute
    else if (c == 0xC3 && n == 0x93) output += 'O';
    else if (c == 0xC4 && n == 0x85) output += 'a';  // a ogonek
    else if (c == 0xC4 && n == 0x84) output += 'A';
    else if (c == 0xC4 && n == 0x87) output += 'c';  // c acute
    else if (c == 0xC4 && n == 0x86) output += 'C';
    else if (c == 0xC4 && n == 0x99) output += 'e';  // e ogonek
    else if (c == 0xC4 && n == 0x98) output += 'E';
    else if (c == 0xC5 && n == 0x82) output += 'l';  // l stroke
    else if (c == 0xC5 && n == 0x81) output += 'L';
    else if (c == 0xC5 && n == 0x84) output += 'n';  // n acute
    else if (c == 0xC5 && n == 0x83) output += 'N';
    else if (c == 0xC5 && n == 0x9B) output += 's';  // s acute
    else if (c == 0xC5 && n == 0x9A) output += 'S';
    else if (c == 0xC5 && n == 0xBA) output += 'z';  // z acute
    else if (c == 0xC5 && n == 0xB9) output += 'Z';
    else if (c == 0xC5 && n == 0xBC) output += 'z';  // z dot
    else if (c == 0xC5 && n == 0xBB) output += 'Z';
    else if (c == 0xC2 && n == 0xB0) output += "deg";
    else if (c == 0xE2) {
      output += '-';
      i += 3;
      continue;
    } else {
      output += '?';
    }
    i += 2;
  }

  return output;
}

template <typename TDoc>
String field(TDoc &doc, const char *name, const char *fallback) {
  if (!doc.containsKey(name) || doc[name].isNull()) {
    return String(fallback);
  }
  const char *value = doc[name].template as<const char *>();
  if (value == nullptr) {
    return String(fallback);
  }
  return normalizeText(String(value));
}

void handleIncomingBytes(const uint8_t *data, size_t len) {
  for (size_t i = 0; i < len; i++) {
    char c = static_cast<char>(data[i]);
    if (c == '\n' || c == '\r') {
      if (rxLine.length() > 0) {
        processJsonLine(rxLine);
        rxLine = "";
      }
      continue;
    }

    if (rxLine.length() >= MAX_JSON_LENGTH) {
      Serial.println("[JSON] Wiadomosc za dluga, czyszcze bufor");
      rxLine = "";
      showNotice("JSON za dlugi", 1200);
      sendBleLine("{\"error\":\"json_too_long\"}");
      return;
    }

    rxLine += c;
  }
}

void processJsonLine(const String &line) {
  Serial.print("[JSON] ");
  Serial.println(line);

  StaticJsonDocument<JSON_DOC_SIZE> doc;
  DeserializationError error = deserializeJson(doc, line);
  if (error) {
    Serial.print("[JSON] Parse error: ");
    Serial.println(error.c_str());
    showNotice("Blad JSON", 1200);
    sendBleLine("{\"error\":\"bad_json\"}");
    return;
  }

  if (doc.containsKey("time")) {
    phoneTime = field(doc, "time");
  }
  if (doc.containsKey("phoneBattery")) {
    phoneBattery = constrain(doc["phoneBattery"].as<int>(), 0, 100);
  }
  if (doc.containsKey("percent")) {
    phoneBattery = constrain(doc["percent"].as<int>(), 0, 100);
  }
  if (doc.containsKey("charging")) {
    phoneCharging = doc["charging"].as<bool>();
  }

  String command = field(doc, "cmd");
  if (command == "version") {
    sendVersionInfo();
    return;
  }

  String type = field(doc, "type");
  uint16_t timeoutMs = doc.containsKey("timeout") ? constrain(doc["timeout"].as<int>(), 0, 30000) : 0;

  if (type == "music") {
    musicTitle = field(doc, "title", "Brak tytulu");
    musicArtist = field(doc, "artist", "Nieznany artysta");
    musicState = field(doc, "state", "paused");
    musicProgress = doc.containsKey("progress") ? constrain(doc["progress"].as<int>(), 0, 100) : -1;
    setScreen(SCREEN_MUSIC, timeoutMs);
  } else if (type == "nav") {
    navInstruction = field(doc, "instruction", "Nawigacja");
    navDistance = field(doc, "distance", "");
    navStreet = field(doc, "street", "");
    navDirection = field(doc, "direction", "");
    setScreen(SCREEN_NAV, timeoutMs);
  } else if (type == "notification") {
    notificationApp = field(doc, "app", "Powiadomienie");
    notificationTitle = field(doc, "title", "");
    notificationText = field(doc, "text", "");
    setScreen(SCREEN_NOTIFICATION, timeoutMs > 0 ? timeoutMs : 5000);
  } else if (type == "battery") {
    phoneBattery = doc.containsKey("percent") ? constrain(doc["percent"].as<int>(), 0, 100) : phoneBattery;
    phoneCharging = doc.containsKey("charging") ? doc["charging"].as<bool>() : phoneCharging;
    setScreen(SCREEN_BATTERY, timeoutMs);
  } else if (type == "call") {
    callName = field(doc, "name", "");
    if (callName.length() == 0) {
      callName = field(doc, "caller", "");
    }
    if (callName.length() == 0) {
      callName = field(doc, "contact", "Nieznany");
    }
    callNumber = field(doc, "number", "");
    callState = field(doc, "state", "incoming");
    setScreen(SCREEN_CALL, timeoutMs);
  } else if (type == "weather") {
    weatherTemp = field(doc, "temp", "--");
    weatherDesc = field(doc, "desc", "");
    weatherCity = field(doc, "city", "");
    setScreen(SCREEN_WEATHER, timeoutMs);
  } else if (type == "home" || type == "time") {
    setScreen(SCREEN_HOME, timeoutMs);
  } else {
    Serial.print("[JSON] Nieznany typ: ");
    Serial.println(type);
    showNotice("Nieznany typ", 1000);
    sendBleLine("{\"error\":\"unknown_type\"}");
    return;
  }

  sendBleLine("{\"ok\":true}");
}

void handleOtaControlBytes(const uint8_t *data, size_t len) {
  for (size_t i = 0; i < len; i++) {
    char c = static_cast<char>(data[i]);
    if (c == '\n' || c == '\r') {
      if (otaControlLine.length() > 0) {
        processOtaControlLine(otaControlLine);
        otaControlLine = "";
      }
      continue;
    }

    if (otaControlLine.length() >= MAX_OTA_CONTROL_LENGTH) {
      otaControlLine = "";
      sendOtaStatus("error", "control_too_long");
      return;
    }
    otaControlLine += c;
  }
}

void processOtaControlLine(const String &line) {
  Serial.print("[OTA CTRL] ");
  Serial.println(line);

  StaticJsonDocument<768> doc;
  DeserializationError error = deserializeJson(doc, line);
  if (error) {
    sendOtaStatus("error", "bad_control_json");
    return;
  }

  String command = field(doc, "cmd");
  if (command == "version") {
    sendVersionInfo();
    return;
  }

  if (command == "begin") {
    String device = field(doc, "device");
    String version = field(doc, "version");
    String sha256 = field(doc, "sha256");
    uint32_t size = doc.containsKey("size") ? doc["size"].as<uint32_t>() : 0;

    if (device != DEVICE_ID) {
      sendOtaStatus("error", "wrong_device");
      return;
    }
    if (version.length() == 0 || size == 0 || sha256.length() != 64) {
      sendOtaStatus("error", "bad_begin");
      return;
    }
    beginOtaUpdate(version, size, sha256);
    return;
  }

  if (command == "finish") {
    finishOtaUpdate();
    return;
  }

  if (command == "abort") {
    abortOtaUpdate("client_abort");
    return;
  }

  sendOtaStatus("error", "unknown_command");
}

void handleOtaData(const uint8_t *data, size_t len) {
  if (!otaActive) {
    sendOtaStatus("error", "ota_not_started");
    return;
  }

  if (otaReceivedSize + len > otaExpectedSize) {
    abortOtaUpdate("too_much_data");
    return;
  }

  size_t written = Update.write(const_cast<uint8_t *>(data), len);
  if (written != len) {
    abortOtaUpdate("flash_write_error");
    return;
  }

  sha256Update(&otaShaContext, data, len);
  otaReceivedSize += len;

  int progress = otaExpectedSize > 0
    ? static_cast<int>((otaReceivedSize * 100UL) / otaExpectedSize)
    : 0;
  progress = constrain(progress, 0, 100);
  otaDisplayProgress = progress;
  otaDisplayState = "Firmware update";
  otaDisplayUntil = millis() + 3000;

  if (progress != lastOtaProgress && millis() - lastOtaNotifyAt > OTA_NOTIFY_INTERVAL_MS) {
    lastOtaNotifyAt = millis();
    lastOtaProgress = progress;
    sendOtaStatus("progress");
  }
}

void sendBleLine(const String &line) {
  if (!bleConnected || txCharacteristic == nullptr) {
    return;
  }
  String out = line + "\n";
  txCharacteristic->setValue(reinterpret_cast<const uint8_t *>(out.c_str()), out.length());
  txCharacteristic->notify();
}

void sendVersionInfo() {
  StaticJsonDocument<256> doc;
  doc["event"] = "version";
  doc["device"] = DEVICE_ID;
  doc["name"] = BLE_DEVICE_NAME;
  doc["version"] = FW_VERSION;
  doc["ota"] = true;

  String out;
  serializeJson(doc, out);
  sendBleLine(out);
  sendOtaLine(out);
}

void sendOtaLine(const String &line) {
  if (!bleConnected || otaControlCharacteristic == nullptr) {
    return;
  }
  String out = line + "\n";
  otaControlCharacteristic->setValue(reinterpret_cast<const uint8_t *>(out.c_str()), out.length());
  otaControlCharacteristic->notify();
}

void sendOtaStatus(const String &state, const String &message) {
  StaticJsonDocument<384> doc;
  doc["event"] = "ota";
  doc["state"] = state;
  doc["device"] = DEVICE_ID;
  doc["version"] = FW_VERSION;
  if (otaTargetVersion.length() > 0) {
    doc["targetVersion"] = otaTargetVersion;
  }
  doc["received"] = otaReceivedSize;
  doc["size"] = otaExpectedSize;
  int progress = otaExpectedSize > 0
    ? static_cast<int>((otaReceivedSize * 100UL) / otaExpectedSize)
    : otaDisplayProgress;
  doc["progress"] = constrain(progress, 0, 100);
  if (message.length() > 0) {
    doc["message"] = message;
  }

  String out;
  serializeJson(doc, out);
  Serial.print("[OTA STATUS] ");
  Serial.println(out);
  sendOtaLine(out);
  sendBleLine(out);
}

bool beginOtaUpdate(const String &version, size_t size, const String &sha256) {
  if (otaActive) {
    abortOtaUpdate("new_update_requested");
  }

  uint32_t freeSketchSpace = ESP.getFreeSketchSpace();
  uint32_t maxSketchSpace = freeSketchSpace > 0x1000
    ? (freeSketchSpace - 0x1000) & 0xFFFFF000
    : 0;
  if (size > maxSketchSpace) {
    sendOtaStatus("error", "firmware_too_large");
    return false;
  }

  if (!Update.begin(size)) {
    Update.printError(Serial);
    sendOtaStatus("error", "update_begin_failed");
    return false;
  }

  otaActive = true;
  otaRebootPending = false;
  otaExpectedSize = size;
  otaReceivedSize = 0;
  otaTargetVersion = version;
  otaExpectedSha256 = sha256;
  otaExpectedSha256.toLowerCase();
  otaDisplayProgress = 0;
  otaDisplayState = "Firmware update";
  otaDisplayUntil = millis() + 5000;
  lastOtaNotifyAt = 0;
  lastOtaProgress = -1;

  mbedtls_sha256_init(&otaShaContext);
  sha256Start(&otaShaContext);

  showNotice("Firmware update", 600);
  sendOtaStatus("ready");
  return true;
}

void finishOtaUpdate() {
  if (!otaActive) {
    sendOtaStatus("error", "ota_not_started");
    return;
  }

  if (otaReceivedSize != otaExpectedSize) {
    abortOtaUpdate("size_mismatch");
    return;
  }

  uint8_t hash[32];
  sha256Finish(&otaShaContext, hash);
  mbedtls_sha256_free(&otaShaContext);
  String actualSha = sha256Hex(hash, sizeof(hash));
  actualSha.toLowerCase();

  if (actualSha != otaExpectedSha256) {
    Serial.print("[OTA] SHA mismatch expected ");
    Serial.print(otaExpectedSha256);
    Serial.print(" got ");
    Serial.println(actualSha);
    Update.abort();
    otaActive = false;
    otaDisplayState = "Update failed";
    otaDisplayUntil = millis() + 10000;
    sendOtaStatus("error", "sha256_mismatch");
    return;
  }

  if (!Update.end(true)) {
    Update.printError(Serial);
    otaActive = false;
    otaDisplayState = "Update failed";
    otaDisplayUntil = millis() + 10000;
    sendOtaStatus("error", "update_end_failed");
    return;
  }

  otaActive = false;
  otaDisplayProgress = 100;
  otaDisplayState = "Update OK, rebooting";
  otaDisplayUntil = millis() + 5000;
  sendOtaStatus("success", "rebooting");

  otaRebootPending = true;
  otaRebootAt = millis() + 1200;
}

void abortOtaUpdate(const String &reason) {
  if (otaActive) {
    Update.abort();
    mbedtls_sha256_free(&otaShaContext);
  }
  otaActive = false;
  otaExpectedSize = 0;
  otaReceivedSize = 0;
  otaDisplayProgress = -1;
  otaDisplayState = "Update failed";
  otaDisplayUntil = millis() + 10000;
  sendOtaStatus("error", reason);
}

String sha256Hex(const uint8_t *hash, size_t len) {
  static const char *hex = "0123456789abcdef";
  String out;
  out.reserve(len * 2);
  for (size_t i = 0; i < len; i++) {
    out += hex[(hash[i] >> 4) & 0x0F];
    out += hex[hash[i] & 0x0F];
  }
  return out;
}

void sha256Start(mbedtls_sha256_context *ctx) {
#if MBEDTLS_VERSION_MAJOR >= 3
  mbedtls_sha256_starts(ctx, 0);
#else
  mbedtls_sha256_starts_ret(ctx, 0);
#endif
}

void sha256Update(mbedtls_sha256_context *ctx, const uint8_t *data, size_t len) {
#if MBEDTLS_VERSION_MAJOR >= 3
  mbedtls_sha256_update(ctx, data, len);
#else
  mbedtls_sha256_update_ret(ctx, data, len);
#endif
}

void sha256Finish(mbedtls_sha256_context *ctx, uint8_t hash[32]) {
#if MBEDTLS_VERSION_MAJOR >= 3
  mbedtls_sha256_finish(ctx, hash);
#else
  mbedtls_sha256_finish_ret(ctx, hash);
#endif
}

void showNotice(const String &text, uint16_t durationMs) {
  noticeText = text;
  noticeUntil = millis() + durationMs;
  lastDrawAt = 0;
}

void setScreen(Screen screen, uint16_t durationMs) {
  currentScreen = screen;
  screenUntil = durationMs > 0 ? millis() + durationMs : 0;
  lastDrawAt = 0;
}

Screen defaultScreen() {
  return SCREEN_HOME;
}

void cycleScreen() {
  int next = static_cast<int>(currentScreen) + 1;
  if (next > static_cast<int>(SCREEN_WEATHER)) {
    next = static_cast<int>(SCREEN_HOME);
  }
  setScreen(static_cast<Screen>(next));
  Serial.print("[UI] Button screen: ");
  Serial.println(next);
}

void handleButton() {
  if (!BUTTON_ENABLED) {
    return;
  }

  bool reading = digitalRead(BUTTON_PIN);
  if (reading != lastButtonReading) {
    lastButtonChange = millis();
  }

  if (millis() - lastButtonChange > 35) {
    if (reading == LOW && !buttonPressed) {
      buttonPressed = true;
      cycleScreen();
    } else if (reading == HIGH) {
      buttonPressed = false;
    }
  }

  lastButtonReading = reading;
}

void updateAutoScreen() {
  if (timerActive(screenUntil)) {
    return;
  }
  if (screenUntil != 0) {
    screenUntil = 0;
    currentScreen = defaultScreen();
    lastDrawAt = 0;
  }
}

bool timerActive(unsigned long until) {
  return until != 0 && static_cast<long>(until - millis()) > 0;
}

void draw() {
  if (!oledReady) {
    delay(10);
    return;
  }

  if (millis() - lastDrawAt < DISPLAY_REFRESH_MS) {
    delay(5);
    return;
  }
  lastDrawAt = millis();

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);

  if (timerActive(noticeUntil)) {
    drawNotice(noticeText);
  } else if (!bleConnected) {
    drawWaiting();
  } else if (otaActive || timerActive(otaDisplayUntil)) {
    drawFirmwareUpdate();
  } else {
    switch (currentScreen) {
      case SCREEN_HOME: drawHome(); break;
      case SCREEN_MUSIC: drawMusic(); break;
      case SCREEN_NAV: drawNavigation(); break;
      case SCREEN_NOTIFICATION: drawNotification(); break;
      case SCREEN_CALL: drawCall(); break;
      case SCREEN_BATTERY: drawBattery(); break;
      case SCREEN_WEATHER: drawWeather(); break;
    }
  }

  display.display();
}

void drawStartup() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(22, 16);
  display.print("Kejmil OLED");
  display.drawRect(16, 36, 96, 10, SSD1306_WHITE);
  display.display();

  for (int p = 0; p <= 100; p += 10) {
    display.fillRect(18, 38, map(p, 0, 100, 0, 92), 6, SSD1306_WHITE);
    display.display();
    delay(70);
  }

  display.clearDisplay();
  display.setCursor(12, 28);
  display.print("Czekam na telefon");
  display.display();
  delay(700);
}

void drawNotice(const String &text) {
  drawHeader("Status");
  display.setTextSize(1);
  display.setCursor(8, 28);
  printFit(text, 20);
}

void drawWaiting() {
  drawHeader("Waiting");
  display.setTextSize(1);
  display.setCursor(5, 24);
  display.print("Waiting for phone...");
  display.setCursor(16, 39);
  display.print("BLE: Kejmil OLED");
}

void drawHeader(const String &title) {
  display.drawRect(0, 0, 128, 11, SSD1306_WHITE);
  display.setCursor(3, 2);
  printFit(title, 15);
  display.setCursor(101, 2);
  if (phoneBattery >= 0) {
    display.print(phoneBattery);
    display.print("%");
  } else {
    display.print("--%");
  }
}

void drawStatusBar() {
  display.drawLine(0, 53, 127, 53, SSD1306_WHITE);
  display.setCursor(0, 56);
  display.print(bleConnected ? "BT:OK " : "BT:-- ");

  display.setCursor(43, 56);
  if (phoneBattery >= 0) {
    display.print("B:");
    display.print(phoneBattery);
    display.print("%");
  } else {
    display.print("B:--");
  }

  display.setCursor(92, 56);
  display.print("Kejmil");
}

void drawHome() {
  drawHeader("Glowny");

  display.setTextSize(2);
  String timeToShow = phoneTime.length() > 0 ? phoneTime : uptimeText();
  drawCenteredText(timeToShow, 18, 2, 9);

  display.setTextSize(1);
  drawCenteredText("Powered by Kejmil", 42, 1, 21);
}

void drawMusic() {
  drawHeader("Muzyka");

  display.drawCircle(8, 20, 3, SSD1306_WHITE);
  display.drawLine(11, 20, 11, 13, SSD1306_WHITE);
  display.drawLine(11, 13, 18, 15, SSD1306_WHITE);

  display.setCursor(24, 14);
  printFit(musicTitle.length() ? musicTitle : "Brak muzyki", 17);
  display.setCursor(24, 24);
  printFit(musicArtist.length() ? musicArtist : "Nieznany artysta", 17);

  display.setCursor(4, 39);
  display.print(musicState == "playing" ? "GRA" : "PAUZA");

  if (musicProgress >= 0) {
    drawProgressBar(40, 39, 83, 7, musicProgress);
  }
}

void drawNavigation() {
  drawHeader("Nawigacja");

  String arrowDirection = navDirection;
  if (arrowDirection.length() == 0) {
    arrowDirection = inferDirectionFromText(navInstruction + " " + navStreet);
  }
  drawNavArrow(arrowDirection, 5, 31);

  display.setCursor(36, 14);
  display.print("skrec za");

  display.setTextSize(2);
  display.setCursor(36, 29);
  printFit(navDistance.length() ? navDistance : "--", 7);
  display.setTextSize(1);

  display.setCursor(36, 46);
  printFit(navInstruction.length() ? navInstruction : "Brak danych", 15);
}

void drawNotification() {
  drawHeader(notificationApp.length() ? notificationApp : "Powiadom.");

  display.setCursor(4, 15);
  printFit(notificationTitle.length() ? notificationTitle : "Nowe powiadom.", 21);

  drawScrollingLine(notificationText.length() ? notificationText : "Brak tekstu", 4, 29, 120);
}

void drawCall() {
  drawHeader("Polaczenie");

  String who = callName.length() ? callName : callNumber;
  if (who.length() == 0) {
    who = "Nieznany";
  }
  if ((who == "Polaczenie" || who == "Rozmowa" || who == "Incoming call" || who == "Phone call" || who == "Unknown" || who == "Nieznany") && callNumber.length() > 0 && callNumber != "Unavailable" && callNumber != "Niedostepny") {
    who = callNumber;
  }

  String stateText = callState;
  if (callState == "incoming") stateText = "Przychodzace";
  else if (callState == "active") stateText = "Trwa rozmowa";
  else if (callState == "ended") stateText = "Zakonczone";

  display.setTextSize(1);
  drawCenteredText(stateText.length() ? stateText : "Przychodzace", 15, 1, 21);

  display.setTextSize(2);
  drawCenteredText(who, 28, 2, 10);

  display.setTextSize(1);
  if (callNumber.length() > 0 && callNumber != who) {
    drawCenteredText(callNumber, 46, 1, 21);
  }
}

void drawBattery() {
  drawHeader("Bateria tel.");

  int percent = phoneBattery >= 0 ? phoneBattery : 0;
  drawBatteryIcon(8, 18, percent, phoneCharging);

  display.setTextSize(2);
  display.setCursor(54, 21);
  if (phoneBattery >= 0) {
    display.print(percent);
    display.print("%");
  } else {
    display.print("--%");
  }

  display.setTextSize(1);
  display.setCursor(54, 43);
  display.print(phoneCharging ? "ladowanie" : "nie laduje");
}

void drawWeather() {
  drawHeader("Pogoda");

  display.drawCircle(16, 24, 8, SSD1306_WHITE);
  display.drawLine(16, 10, 16, 14, SSD1306_WHITE);
  display.drawLine(16, 34, 16, 38, SSD1306_WHITE);
  display.drawLine(2, 24, 6, 24, SSD1306_WHITE);
  display.drawLine(26, 24, 30, 24, SSD1306_WHITE);

  display.setTextSize(2);
  display.setCursor(42, 15);
  printFit(weatherTemp.length() ? weatherTemp : "--", 7);

  display.setTextSize(1);
  display.setCursor(42, 35);
  printFit(weatherDesc, 15);
  display.setCursor(42, 45);
  printFit(weatherCity, 15);
}

void drawFirmwareUpdate() {
  drawHeader("Firmware update");

  int progress = otaDisplayProgress >= 0 ? otaDisplayProgress : 0;
  display.setCursor(4, 16);
  if (otaDisplayState.length() > 0) {
    printFit(otaDisplayState, 21);
  } else {
    display.print("Firmware update");
  }

  display.setTextSize(2);
  display.setCursor(42, 25);
  display.print(progress);
  display.print("%");
  display.setTextSize(1);

  drawProgressBar(5, 39, 118, 6, progress);
  display.setCursor(8, 46);
  if (otaDisplayState == "Update failed") {
    display.print("Update failed");
  } else if (otaDisplayState == "Update OK, rebooting") {
    display.print("Update OK, rebooting");
  } else {
    display.print("Do not disconnect");
  }
}

void drawBatteryIcon(int x, int y, int percent, bool charging) {
  display.drawRect(x, y, 36, 18, SSD1306_WHITE);
  display.fillRect(x + 36, y + 5, 3, 8, SSD1306_WHITE);

  int fillWidth = map(constrain(percent, 0, 100), 0, 100, 0, 32);
  if (fillWidth > 0) {
    display.fillRect(x + 2, y + 2, fillWidth, 14, SSD1306_WHITE);
  }

  if (charging) {
    display.setTextColor(SSD1306_BLACK);
    display.setCursor(x + 14, y + 5);
    display.print("+");
    display.setTextColor(SSD1306_WHITE);
  }
}

void drawProgressBar(int x, int y, int w, int h, int percent) {
  display.drawRect(x, y, w, h, SSD1306_WHITE);
  int fillWidth = map(constrain(percent, 0, 100), 0, 100, 0, w - 2);
  if (fillWidth > 0) {
    display.fillRect(x + 1, y + 1, fillWidth, h - 2, SSD1306_WHITE);
  }
}

void drawNavArrow(const String &direction, int x, int y) {
  if (direction == "left") {
    display.drawLine(x + 23, y, x + 5, y, SSD1306_WHITE);
    display.drawLine(x + 23, y + 1, x + 5, y + 1, SSD1306_WHITE);
    display.fillTriangle(x + 3, y, x + 12, y - 9, x + 12, y + 9, SSD1306_WHITE);
  } else if (direction == "right") {
    display.drawLine(x + 3, y, x + 21, y, SSD1306_WHITE);
    display.drawLine(x + 3, y + 1, x + 21, y + 1, SSD1306_WHITE);
    display.fillTriangle(x + 23, y, x + 14, y - 9, x + 14, y + 9, SSD1306_WHITE);
  } else if (direction == "straight") {
    display.drawLine(x + 12, y + 10, x + 12, y - 9, SSD1306_WHITE);
    display.drawLine(x + 13, y + 10, x + 13, y - 9, SSD1306_WHITE);
    display.fillTriangle(x + 12, y - 12, x + 4, y - 3, x + 20, y - 3, SSD1306_WHITE);
  } else if (direction == "roundabout") {
    display.drawCircle(x + 12, y, 9, SSD1306_WHITE);
    display.fillTriangle(x + 18, y - 8, x + 24, y - 5, x + 18, y - 2, SSD1306_WHITE);
  } else if (direction == "uturn") {
    display.drawRoundRect(x + 5, y - 10, 18, 18, 7, SSD1306_WHITE);
    display.fillTriangle(x + 5, y + 8, x + 12, y + 2, x + 12, y + 14, SSD1306_WHITE);
  } else {
    display.drawLine(x + 3, y, x + 21, y, SSD1306_WHITE);
    display.drawLine(x + 3, y + 1, x + 21, y + 1, SSD1306_WHITE);
    display.fillTriangle(x + 23, y, x + 14, y - 9, x + 14, y + 9, SSD1306_WHITE);
  }
}

String inferDirectionFromText(const String &text) {
  String lower = normalizeText(text);
  lower.toLowerCase();

  if (lower.indexOf("prawo") >= 0 || lower.indexOf("right") >= 0) {
    return "right";
  }
  if (lower.indexOf("lewo") >= 0 || lower.indexOf("left") >= 0) {
    return "left";
  }
  if (lower.indexOf("prosto") >= 0 || lower.indexOf("straight") >= 0 || lower.indexOf("continue") >= 0) {
    return "straight";
  }
  if (lower.indexOf("rondo") >= 0 || lower.indexOf("roundabout") >= 0) {
    return "roundabout";
  }
  if (lower.indexOf("zawroc") >= 0 || lower.indexOf("uturn") >= 0 || lower.indexOf("u-turn") >= 0) {
    return "uturn";
  }
  return "right";
}

void drawScrollingLine(const String &text, int x, int y, int width) {
  uint8_t visibleChars = width / 6;
  String safe = normalizeText(text);

  if (safe.length() <= visibleChars) {
    display.setCursor(x, y);
    display.print(safe);
    return;
  }

  uint16_t cycle = safe.length() + 4;
  uint16_t offset = (millis() / SCROLL_STEP_MS) % cycle;
  String padded = safe + "    " + safe;
  display.setCursor(x, y);
  display.print(padded.substring(offset, offset + visibleChars));
}

void printFit(const String &text, uint8_t maxChars) {
  display.print(fitText(text, maxChars));
}

void drawCenteredText(const String &text, int y, uint8_t textSize, uint8_t maxChars) {
  String fitted = fitText(text, maxChars);
  display.setTextSize(textSize);
  display.setCursor(centeredX(fitted, textSize), y);
  display.print(fitted);
}

int centeredX(const String &text, uint8_t textSize) {
  String safe = normalizeText(text);
  int width = safe.length() * 6 * textSize;
  return max(0, (SCREEN_WIDTH - width) / 2);
}

String fitText(const String &text, uint8_t maxChars) {
  String safe = normalizeText(text);
  if (safe.length() <= maxChars) {
    return safe;
  }
  if (maxChars <= 3) {
    return safe.substring(0, maxChars);
  }
  return safe.substring(0, maxChars - 3) + "...";
}

String uptimeText() {
  unsigned long seconds = millis() / 1000;
  uint8_t hh = (seconds / 3600) % 24;
  uint8_t mm = (seconds / 60) % 60;
  char buffer[6];
  snprintf(buffer, sizeof(buffer), "%02u:%02u", hh, mm);
  return String(buffer);
}
