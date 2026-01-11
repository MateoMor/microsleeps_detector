# Microsleeps Detector

This project is a **real-time drowsiness detection system** built to demonstrate how to **monitor driver alertness using computer vision and IoT technologies**.  

- 🚀 Detect microsleep episodes in real-time using facial landmark analysis
- ⚡ Stream video from ESP32-CAM over WiFi with MJPEG protocol
- 🔧 Integrate Google MediaPipe for face mesh detection on Android
- 🎨 Visualize facial landmarks and drowsiness metrics with custom overlay views
- 📦 Run background drowsiness monitoring as a foreground Android service
- 🌍 Combine embedded systems (ESP32) with mobile app development (Android/Kotlin)

## Features

| Feature              | Description             |
| -------------------- | ----------------------- |
| **Real-time Face Detection** | Uses Google MediaPipe Face Landmarker to detect 468 facial landmarks in live camera feed or MJPEG stream |
| **EAR Analysis** | Calculates Eye Aspect Ratio (EAR) with exponential moving average smoothing to detect eye closure events |
| **ESP32 Camera Streaming** | ESP32-S3-CAM module configured as WiFi access point serving MJPEG video stream|
| **Dual Input Modes** | Support for both local CameraX capture and remote MJPEG stream from ESP32 with automatic reconnection |
| **Visual Feedback** | Custom overlay rendering with facial landmarks, bounding boxes, and real-time drowsiness metrics dashboard |
| **Audio Alerts** | Configurable alarm system that triggers on prolonged eye closure or excessive head nodding |
| **Background Service** | Foreground service implementation for continuous monitoring with notification support |

## Project Structure

```
microsleeps_detector/
├── esp32/                        # ESP32-S3-CAM firmware
│   ├── esp32.ino                 # Main Arduino sketch with MJPEG server
│   ├── camera_pins.h             # Pin definitions for OV2640 camera
│   ├── camera_index.h            # HTML index page for web interface
│   └── partitions.csv            # Memory partition configuration
│
├── 3d_model/                     # 3D printable parts
│   └── camera_back.gcode         # G-code for camera mounting bracket
│
└── microsleeps_detector/         # Android application
    └── app/
        └── src/main/
            ├── java/com/example/microsleeps_detector/
            │   ├── MainActivity.kt                    # Main activity with navigation
            │   ├── CameraFragment.kt                  # Local camera capture with CameraX
            │   ├── StreamFragment.kt                  # Remote MJPEG stream viewer
            │   ├── Dashboard.kt                       # Metrics visualization fragment
            │   ├── FaceLandmarkerHelper.kt           # MediaPipe Face Landmarker wrapper
            │   ├── FaceAnalysis.kt                    # EAR and head nod computation
            │   ├── DrowsinessDetectionService.kt     # Background monitoring service
            │   ├── AlarmPlayer.kt                     # Audio alert management
            │   ├── BaseFaceDetectionFragment.kt      # Base fragment with common functionality
            │   └── ui/
            │       ├── OverlayView.kt                # Custom view for facial landmarks
            │       └── DashboardRenderer.kt          # Dashboard UI rendering
            │
            ├── res/                                   # Android resources (layouts, drawables, etc.)
            └── AndroidManifest.xml                    # App permissions and components
```

## How to Install Microsleeps Detector

### Prerequisites

- **Android Studio** (Hedgehog or later) with Kotlin plugin
- **Arduino IDE** (1.8.19 or later) with ESP32 board support
- **Android device** running API 25+ (Android 7.0+)
- **ESP32-S3-CAM module** with OV2640 camera sensor
- **USB cable** for ESP32 programming and Android device connection

### Installation Steps

1. **Clone this repository**

   ```bash
   git clone https://github.com/MateoMor/microsleeps_detector.git
   cd microsleeps_detector
   ```

2. **Configure and upload ESP32 firmware**

   ```bash
   # Open Arduino IDE
   # Install ESP32 board support: https://docs.espressif.com/projects/arduino-esp32/en/latest/installing.html
   # Open esp32/esp32.ino
   # Select board: "ESP32S3 Dev Module"
   # Configure: PSRAM: "OPI PSRAM", Partition: "Huge APP (3MB No OTA/1MB SPIFFS)"
   # Update WiFi credentials in esp32.ino if needed (default: SSID="ESP32S3_CAM_AP", password="esp32s3cam")
   # Upload to ESP32-S3-CAM module
   ```

3. **Build and install Android app**

   ```bash
   cd microsleeps_detector
   # Open project in Android Studio
   # Sync Gradle dependencies
   # Connect Android device via USB or use emulator
   # Run the app (Shift+F10)
   ```

4. **Connect to ESP32 camera stream**
   ```bash
   # On Android device, connect to ESP32 WiFi network: "ESP32S3_CAM_AP"
   # Open the app and navigate to "Stream" tab
   # Enter ESP32 IP address (check Serial Monitor, typically 192.168.4.1)
   # Stream endpoint: http://<ESP32_IP>/stream
   ```

## Tech Stack

| Category         | Technology     |
| ---------------- | -------------- |
| **Mobile Platform** | Android (Kotlin), Jetpack Navigation, View Binding |
| **Computer Vision** | Google MediaPipe Tasks Vision (Face Landmarker 0.10.18) |
| **Camera** | CameraX 1.5.1, MJPEG Stream (ipcam-view 2.1.0) |
| **Embedded System** | ESP32-S3 with Arduino framework, OV2640 camera sensor |
| **Networking** | WiFi (ESP32 AP mode), HTTP/MJPEG streaming, OkHttp 4.12.0 |
| **Build Tools** | Gradle 8.13 (Kotlin DSL), Android Gradle Plugin 8.x |