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
// Configuración del Access Point
// =============================
const char* ssid = "ESP32S3_CAM_AP";
const char* password = "esp32s3cam";  // debe tener mínimo 8 caracteres

// =============================
// Servidor HTTP
// =============================
WebServer server(80);

// Control de FPS
const int targetFPS = 8;  // número de imágenes por segundo
unsigned long lastFrameTime = 0;
unsigned long frameInterval = 1000 / targetFPS; // milisegundos entre frames

// Handler para el streaming MJPEG
void handleStream() {
  WiFiClient client = server.client();
  camera_fb_t * fb = NULL;

  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: multipart/x-mixed-replace; boundary=frame");
  client.println();

  while (true) {
  unsigned long now = millis();
  if (now - lastFrameTime >= frameInterval) {
    lastFrameTime = now;

    fb = esp_camera_fb_get();
    if (!fb) {
      Serial.println("❌ Error al capturar frame");
      break;
    }

    client.printf("--frame\r\n");
    client.printf("Content-Type: image/jpeg\r\n");
    client.printf("Content-Length: %u\r\n\r\n", fb->len);
    client.write(fb->buf, fb->len);
    client.printf("\r\n");

    esp_camera_fb_return(fb);
  }

  if (!client.connected()) break;
}
  Serial.println("📴 Cliente desconectado");
}

// Inicializa el servidor HTTP
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

  // --- Configuración del módulo de cámara ---
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
  config.frame_size = FRAMESIZE_VGA;
  config.jpeg_quality = 10;
  config.fb_count = 2;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = CAMERA_FB_IN_PSRAM;

  // --- Inicializar cámara ---
  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("❌ Error iniciando cámara: 0x%x\n", err);
    return;
  }

  Serial.println("📸 Cámara inicializada correctamente");

  // --- Crear punto de acceso ---
  WiFi.mode(WIFI_AP);
  bool result = WiFi.softAP(ssid, password);
  if (!result) {
    Serial.println("❌ Error iniciando Access Point");
    return;
  }

  IPAddress IP = WiFi.softAPIP();
  Serial.println("\n✅ Access Point creado!");
  Serial.print("🔹 SSID: "); Serial.println(ssid);
  Serial.print("🔹 PASS: "); Serial.println(password);
  Serial.print("🌐 Conéctate y entra a: http://");
  Serial.println(IP);
  Serial.println("➡ Luego abre /stream para ver el video.");

  // --- Iniciar servidor de la cámara ---
  startCameraServer();

  // Encender LED indicador
  pinMode(LED_GPIO_NUM, OUTPUT);
  digitalWrite(LED_GPIO_NUM, HIGH);
}

void loop() {
  server.handleClient();  // necesario para atender solicitudes
}
