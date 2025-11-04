# Sistema de Evaluación del Modelo de Detección de Somnolencia

Este script evalúa el rendimiento del modelo de detección de somnolencia utilizando el dataset Driver Drowsiness Dataset (DDD).

## 📋 Descripción

El sistema utiliza:
- **MediaPipe Face Landmarker** para detección de puntos faciales
- **Eye Aspect Ratio (EAR)** para determinar el estado de los ojos
- **Métricas de clasificación** estándar para evaluar el rendimiento
- **Generación automática de informe en PDF** con visualizaciones

## 🚀 Uso

### Ejecución básica

```bash
python drowsiness.py
```

### Configuración

Puedes ajustar los siguientes parámetros en el script:

- `MAX_SAMPLES`: Número de muestras por clase (default: 500)
  - Reducir para pruebas más rápidas
  - Aumentar para evaluación completa

- `ear_closed_threshold`: Umbral de EAR para detectar ojos cerrados (default: 0.10)

## 📊 Salida

El script genera:

1. **Consola**: Métricas en tiempo real durante el procesamiento
2. **PDF**: Informe completo con:
   - Portada con información del modelo
   - Métricas de clasificación (Accuracy, Precision, Recall, F1-Score)
   - Matriz de confusión (absoluta y normalizada)
   - Análisis de tiempos de inferencia
   - Distribución de valores EAR

## 📈 Métricas Evaluadas

### Clasificación
- **Accuracy**: Porcentaje total de aciertos
- **Precision**: Porcentaje de detecciones de somnolencia correctas
- **Recall**: Porcentaje de casos de somnolencia detectados
- **F1-Score**: Media armónica de precision y recall

### Rendimiento
- **Tiempo promedio de inferencia**: En milisegundos
- **FPS estimado**: Frames por segundo procesables
- **Distribución de tiempos**: Histogramas y estadísticas

### Análisis EAR
- **Distribución por clase**: Drowsy vs Non Drowsy
- **Separabilidad**: Diferencia entre medias
- **Validación de umbral**: Efectividad del umbral 0.10

## 🔧 Requisitos del Sistema

- Python 3.10+
- Dependencias instaladas (ver requirements)
- Dataset en la ruta especificada
- Modelo face_landmarker.task

## 📁 Estructura de Archivos

```
testing/
├── drowsiness.py              # Script principal
├── README.md                  # Este archivo
└── informe_modelo_somnolencia.pdf  # Informe generado (después de ejecutar)
```

## ⚙️ Personalización

### Cambiar número de muestras

Edita la variable `MAX_SAMPLES` en `main()`:

```python
MAX_SAMPLES = 500  # 500 por clase = 1000 total
```

### Cambiar umbral de detección

Modifica el parámetro en `FaceAnalysis`:

```python
self.face_analysis = FaceAnalysis(
    ear_closed_threshold=0.10  # Ajustar este valor
)
```

### Rutas personalizadas

Modifica las constantes en `main()`:

```python
DATASET_PATH = "ruta/al/dataset"
MODEL_PATH = "ruta/al/modelo.task"
OUTPUT_PDF = "ruta/salida/informe.pdf"
```

## 🐛 Solución de Problemas

### Error: No se encuentra el dataset
- Verifica que la carpeta dataset existe
- Comprueba que contiene las subcarpetas "Drowsy" y "Non Drowsy"

### Error: No se encuentra el modelo
- Asegúrate de que face_landmarker.task está en la ruta correcta
- Descarga el modelo desde MediaPipe si es necesario

### Bajo rendimiento
- Reduce MAX_SAMPLES para pruebas rápidas
- Considera usar GPU si está disponible

### Errores de memoria
- Reduce MAX_SAMPLES
- Procesa el dataset en lotes más pequeños

## 📝 Notas

- El script resetea el estado del analizador entre clases para evitar contaminación
- Las imágenes donde no se detecta rostro se cuentan como "Non Drowsy"
- El tiempo de ejecución depende del número de muestras y hardware

## 🎯 Interpretación de Resultados

- **Accuracy > 90%**: Excelente rendimiento
- **Recall alto**: Buena detección de casos de somnolencia (importante para seguridad)
- **Precision alto**: Pocas falsas alarmas (importante para usabilidad)
- **Tiempo < 50ms**: Adecuado para tiempo real (>20 FPS)

## 📧 Soporte

Para problemas o preguntas, consulta la documentación del proyecto.
