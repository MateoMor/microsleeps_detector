#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>

// =============================
// Configuración de la cámara (ESP32-S3-CAM con OV2640)
// =============================
#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     15
#define SIOD_GPIO_NUM     4
#define SIOC_GPIO_NUM     5

#define Y9_GPIO_NUM       16
#define Y8_GPIO_NUM       17
#define Y7_GPIO_NUM       18
#define Y6_GPIO_NUM       12
#define Y5_GPIO_NUM       10
#define Y4_GPIO_NUM       8
#define Y3_GPIO_NUM       9
#define Y2_GPIO_NUM       11

#define VSYNC_GPIO_NUM    6
#define HREF_GPIO_NUM     7
#define PCLK_GPIO_NUM     13

#define LED_GPIO_NUM      2  // LED rojo integrado (algunos módulos)

// =============================
// Credenciales de red Wi-Fi EXISTENTE
// =============================
const char* ssid = "ESP32S3_CAM_AP";
const char* password = "esp32s3cam";

// =============================
// Servidor HTTP
// =============================
WebServer server(80);

// Control de FPS
const int targetFPS = 8;
unsigned long lastFrameTime = 0;
unsigned long frameInterval = 1000 / targetFPS;

// ========================================================
//     🔄 SISTEMA DE RECONEXIÓN AUTOMÁTICA AL WIFI
// ========================================================

unsigned long lastReconnectAttempt = 0;

// Conexión inicial (reintento infinito)
void connectToWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  Serial.println("📶 Intentando conectar a WiFi...");

  while (WiFi.status() != WL_CONNECTED) {
    Serial.print(".");
    delay(500);
  }

  Serial.println("\n✅ WiFi conectado");
  Serial.print("🌐 IP: ");
  Serial.println(WiFi.localIP());
}

// Reconectar si se pierde la señal (no bloquea)
void maintainWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;

  if (millis() - lastReconnectAttempt >= 2000) {
    Serial.println("🔄 WiFi desconectado, intentando reconectar...");
    WiFi.disconnect();
    WiFi.reconnect();
    lastReconnectAttempt = millis();
  }
}

// =============================
// Handler para el streaming MJPEG
// =============================
void handleStream() {
  WiFiClient client = server.client();
  camera_fb_t * fb = NULL;

  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: multipart/x-mixed-replace; boundary=frame");
  client.println();

  Serial.println("📲 Cliente conectado al stream");

  while (true) {

    if (!client.connected()) {
      Serial.println("❌ Cliente desconectado");
      client.stop();
      break;
    }

    unsigned long loopStart = millis();

    if (millis() - lastFrameTime >= frameInterval) {
      lastFrameTime = millis();

      unsigned long t0 = millis();

      fb = esp_camera_fb_get();
      unsigned long t1 = millis();

      if (!fb) {
        Serial.println("❌ Error al capturar frame");
        client.stop();
        break;
      }

      client.printf("--frame\r\n");
      client.printf("Content-Type: image/jpeg\r\n");
      client.printf("Content-Length: %u\r\n\r\n", fb->len);

      unsigned long t2 = millis();

      size_t sent = client.write(fb->buf, fb->len);

      Serial.printf("Bytes enviados: %u\n", sent);

      if (sent == 0 || sent < fb->len) {
        Serial.println("❌ Error durante write() -> Cliente dejó de recibir datos");
        esp_camera_fb_return(fb);
        client.stop();
        break;
      }

      client.print("\r\n");

      unsigned long t3 = millis();

      esp_camera_fb_return(fb);

      unsigned long t4 = millis();

      Serial.println("🕒 --- Tiempos de operación (ms) ---");
      Serial.printf("  Captura:        %lu ms\n", t1 - t0);
      Serial.printf("  HTTP headers:   %lu ms\n", t2 - t1);
      Serial.printf("  Envío imagen:   %lu ms\n", t3 - t2);
      Serial.printf("  Devolver frame: %lu ms\n", t4 - t3);
      Serial.printf("  Ciclo total:    %lu ms\n", t4 - loopStart);
      Serial.println("------------------------------\n");

      yield();
    }
  }

  Serial.println("📴 Stream finalizado.");
}

// =============================
// Inicializa el servidor HTTP
// =============================
void startCameraServer() {
  server.on("/", []() {
    server.send(200, "text/plain", "Servidor MJPEG activo. Accede a /stream para ver el video.");
  });

  server.on("/stream", HTTP_GET, handleStream);
  server.begin();
  Serial.println("📡 Servidor HTTP iniciado en /stream");
}

// =============================
// Configuración inicial
// =============================
void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(true);
  Serial.println("\n🔧 Iniciando cámara...");

  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_QCIF;
  config.jpeg_quality = 10;
  config.fb_count = 2;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = CAMERA_FB_IN_PSRAM;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("❌ Error iniciando cámara: 0x%x\n", err);
    return;
  }

  Serial.println("📸 Cámara inicializada correctamente");

  connectToWiFi();
  startCameraServer();

  pinMode(LED_GPIO_NUM, OUTPUT);
  digitalWrite(LED_GPIO_NUM, HIGH);
}

void loop() {
  maintainWiFi();      // 🔄 Reintenta conexión si se cae
  server.handleClient();
}
