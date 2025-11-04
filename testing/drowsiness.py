"""
Sistema de Evaluación del Modelo de Detección de Somnolencia
Utiliza MediaPipe Face Landmarker y métricas basadas en EAR (Eye Aspect Ratio)
"""

import os
import cv2
import numpy as np
import mediapipe as mp
from pathlib import Path
import time
from datetime import datetime
from dataclasses import dataclass
from typing import List, Tuple, Optional
import matplotlib.pyplot as plt
import seaborn as sns
from matplotlib.backends.backend_pdf import PdfPages
from sklearn.metrics import (
    confusion_matrix, 
    classification_report, 
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    roc_curve,
    auc
)
import warnings
warnings.filterwarnings('ignore')


@dataclass
class AnalysisResult:
    """Resultado del análisis de un frame"""
    ear_left: float
    ear_right: float
    ear_average: float
    eyes_closed: bool
    inference_time_ms: float


class FaceAnalysis:
    """
    Análisis de rostro basado en landmarks de MediaPipe.
    Implementa la misma lógica que la app Android.
    """
    
    def __init__(
        self,
        ear_closed_threshold: float = 0.10,
        ear_smoothing_alpha: float = 0.3
    ):
        self.ear_closed_threshold = ear_closed_threshold
        self.ear_smoothing_alpha = ear_smoothing_alpha
        self.ear_avg_ema = None
        
    def update(self, points: List[Tuple[float, float]]) -> Optional[AnalysisResult]:
        """
        Procesa los landmarks y calcula métricas de somnolencia
        
        Args:
            points: Lista de tuplas (x, y) normalizadas [0,1] de landmarks faciales
            
        Returns:
            AnalysisResult con métricas calculadas o None si no hay suficientes landmarks
        """
        if len(points) <= 387:
            return None
            
        start_time = time.time()
        
        # Calcular EAR para cada ojo
        left_ear = self._calculate_ear(points, left=True)
        right_ear = self._calculate_ear(points, left=False)
        avg_ear = (left_ear + right_ear) / 2.0
        
        # FaceAnalysis - EMA, line 156
        if self.ear_avg_ema is None:
            self.ear_avg_ema = avg_ear
        else:
            self.ear_avg_ema = self.ear_avg_ema + self.ear_smoothing_alpha * (avg_ear - self.ear_avg_ema)
        
        ear_smoothed = self.ear_avg_ema
        eyes_closed = ear_smoothed < self.ear_closed_threshold
        
        inference_time = (time.time() - start_time) * 1000  # convertir a ms
        
        return AnalysisResult(
            ear_left=left_ear,
            ear_right=right_ear,
            ear_average=ear_smoothed,
            eyes_closed=eyes_closed,
            inference_time_ms=inference_time
        )
    
    def _calculate_ear(self, points: List[Tuple[float, float]], left: bool) -> float:
        """
        Calcula Eye Aspect Ratio usando índices de MediaPipe Face Mesh
        
        EAR = (||p2 - p6|| + ||p3 - p5||) / (2 * ||p1 - p4||)
        """
        # Índices según MediaPipe Face Mesh
        if left:
            p1_idx, p4_idx = 33, 133   
            p2_idx, p6_idx = 160, 144 
            p3_idx, p5_idx = 158, 153 
        else:
            p1_idx, p4_idx = 263, 362
            p2_idx, p6_idx = 387, 373
            p3_idx, p5_idx = 385, 380
        
        
        vertical_1 = self._euclidean_distance(points[p2_idx], points[p6_idx])
        vertical_2 = self._euclidean_distance(points[p3_idx], points[p5_idx])
        horizontal = self._euclidean_distance(points[p1_idx], points[p4_idx])
        
        if horizontal > 0:
            ear = (vertical_1 + vertical_2) / (2.0 * horizontal)
        else:
            ear = 0.0
            
        return ear
    
    @staticmethod
    def _euclidean_distance(p1: Tuple[float, float], p2: Tuple[float, float]) -> float:
        """Calcula distancia euclidiana entre dos puntos"""
        return np.sqrt((p1[0] - p2[0])**2 + (p1[1] - p2[1])**2)


class DrowsinessDetector:
    """Detector de somnolencia usando MediaPipe Face Landmarker"""
    
    def __init__(self, model_path: str):
        """
        Inicializa el detector
        
        Args:
            model_path: Ruta al modelo de MediaPipe
        """
        self.model_path = model_path
        self.face_analysis = FaceAnalysis()
        self.face_landmarker = None
        self._setup_landmarker()
        
    def _setup_landmarker(self):
        """Configura MediaPipe Face Landmarker"""
        BaseOptions = mp.tasks.BaseOptions
        FaceLandmarker = mp.tasks.vision.FaceLandmarker
        FaceLandmarkerOptions = mp.tasks.vision.FaceLandmarkerOptions
        VisionRunningMode = mp.tasks.vision.RunningMode
        
        options = FaceLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=self.model_path),
            running_mode=VisionRunningMode.IMAGE,
            num_faces=1,
            min_face_detection_confidence=0.5,
            min_face_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            output_face_blendshapes=True
        )
        
        self.face_landmarker = FaceLandmarker.create_from_options(options)
    
    def detect(self, image_path: str) -> Optional[AnalysisResult]:
        """
        Detecta somnolencia en una imagen
        
        Args:
            image_path: Ruta a la imagen
            
        Returns:
            AnalysisResult con las métricas o None si no se detecta rostro
        """
        # Leer imagen
        image = cv2.imread(image_path)
        if image is None:
            return None
            
        # Convertir a RGB
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        
        # Crear objeto MPImage
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=image_rgb)
        
        # Detectar landmarks
        detection_result = self.face_landmarker.detect(mp_image)
        
        if not detection_result.face_landmarks:
            return None
        
        # Obtener landmarks del primer rostro detectado
        landmarks = detection_result.face_landmarks[0]
        points = [(lm.x, lm.y) for lm in landmarks]
        
        # Resetear el analizador para cada imagen (sin EMA entre imágenes)
        self.face_analysis.ear_avg_ema = None
        
        # Analizar
        result = self.face_analysis.update(points)
        
        return result
    
    def close(self):
        """Cierra el detector"""
        if self.face_landmarker:
            self.face_landmarker.close()


class ModelEvaluator:
    """Evaluador del modelo de detección de somnolencia"""
    
    def __init__(self, dataset_path: str, model_path: str):
        """
        Inicializa el evaluador
        
        Args:
            dataset_path: Ruta al directorio del dataset
            model_path: Ruta al modelo de MediaPipe
        """
        self.dataset_path = Path(dataset_path)
        self.model_path = model_path
        self.detector = None
        self.results = {
            'true_labels': [],
            'predicted_labels': [],
            'inference_times': [],
            'ear_values': []
        }
        
    def load_dataset(self, max_samples_per_class: int = 500) -> Tuple[List[str], List[int]]:
        """
        Carga las rutas de las imágenes y sus etiquetas
        
        Args:
            max_samples_per_class: Máximo de muestras por clase (para reducir tiempo de prueba)
            
        Returns:
            Tupla de (rutas_imagenes, etiquetas)
        """
        drowsy_path = self.dataset_path / "Drowsy"
        non_drowsy_path = self.dataset_path / "Non Drowsy"
        
        image_paths = []
        labels = []
        
        # Cargar imágenes somnolencia
        drowsy_images = list(drowsy_path.glob("*.jpg")) + list(drowsy_path.glob("*.png"))
        drowsy_images = drowsy_images[:max_samples_per_class]
        image_paths.extend([str(p) for p in drowsy_images])
        labels.extend([True] * len(drowsy_images))  # 1 = Drowsy
        
        # Cargar imágenes no somnolencia
        non_drowsy_images = list(non_drowsy_path.glob("*.jpg")) + list(non_drowsy_path.glob("*.png"))
        non_drowsy_images = non_drowsy_images[:max_samples_per_class]
        image_paths.extend([str(p) for p in non_drowsy_images])
        labels.extend([False] * len(non_drowsy_images))  # 0 = Non Drowsy
        
        print(f"Dataset cargado:")
        print(f"  - Imágenes Drowsy: {len(drowsy_images)}")
        print(f"  - Imágenes Non Drowsy: {len(non_drowsy_images)}")
        print(f"  - Total: {len(image_paths)}")
        
        return image_paths, labels
    
    def evaluate(self, max_samples_per_class: int = 500, show_progress: int = 100):
        """
        Evalúa el modelo con el dataset
        
        Args:
            max_samples_per_class: Máximo de muestras por clase
        """
        print("\n" + "="*60)
        print("EVALUACIÓN DEL MODELO DE DETECCIÓN DE SOMNOLENCIA")
        print("="*60)
        
        print("\n1. Cargando dataset...")
        image_paths, true_labels = self.load_dataset(max_samples_per_class)
        
        print("\n2. Inicializando detector...")
        self.detector = DrowsinessDetector(self.model_path)
        
        print("\n3. Procesando imágenes...")
        predicted_labels = []
        inference_times = []
        ear_values = []
        failed_count = 0
        
        for i, image_path in enumerate(image_paths):
            if (i + 1) % show_progress == 0:
                print(f"   Procesadas {i + 1}/{len(image_paths)} imágenes...")
            
            result = self.detector.detect(image_path)
            
            if result is None:
                # Detection failed . -. 
                predicted_labels.append(False) 
                failed_count += 1
            else:
                predicted_labels.append(result.eyes_closed)
                inference_times.append(result.inference_time_ms)
                ear_values.append(result.ear_average)
        
        # Save Results
        self.results['true_labels'] = true_labels
        self.results['predicted_labels'] = predicted_labels
        self.results['inference_times'] = inference_times
        self.results['ear_values'] = ear_values
        
        print(f"\n   Total procesadas: {len(image_paths)}")
        print(f"   Fallos de detección: {failed_count} ({failed_count/len(image_paths)*100:.2f}%)")
        
        print("\n4. Calculando métricas...")
        self._calculate_metrics()
        
        self.detector.close()
        
    def _calculate_metrics(self):
        """Calcula métricas de rendimiento"""
        y_true = np.array(self.results['true_labels'])
        y_pred = np.array(self.results['predicted_labels'])

        # Measures the proportion of exactitud predictions
        accuracy = accuracy_score(y_true, y_pred)
        
        # How correct is the model
        precision = precision_score(y_true, y_pred, zero_division=0)
        
        # How complete is the model
        recall = recall_score(y_true, y_pred, zero_division=0)
                
        # Harmonic mean of precision and recall
        f1 = f1_score(y_true, y_pred, zero_division=0)

        # Mistakes analysis of the model
        cm = confusion_matrix(y_true, y_pred)
        
        # Inference Time Statistics: Measure how fast the model processes each image
        # Critical for real-time applications (need <33ms for 30 FPS)
        if self.results['inference_times']:
            avg_time = np.mean(self.results['inference_times'])
            std_time = np.std(self.results['inference_times'])
            min_time = np.min(self.results['inference_times'])
            max_time = np.max(self.results['inference_times'])
        else:
            avg_time = std_time = min_time = max_time = -1
        
        # Later used in the pdf report
        self.results['metrics'] = {
            'accuracy': accuracy,
            'precision': precision,
            'recall': recall,
            'f1_score': f1,
            'confusion_matrix': cm,
            'avg_inference_time_ms': avg_time,
            'std_inference_time_ms': std_time,
            'min_inference_time_ms': min_time,
            'max_inference_time_ms': max_time
        }
        
        # Print results :D
        print("\n" + "="*60)
        print("RESULTADOS DE LA EVALUACIÓN")
        print("="*60)
        print(f"\nMétricas de Clasificación:")
        print(f"  - Accuracy (Precisión):     {accuracy*100:.2f}%")
        print(f"  - Precision (Exactitud):    {precision*100:.2f}%")
        print(f"  - Recall (Sensibilidad):    {recall*100:.2f}%")
        print(f"  - F1-Score:                 {f1*100:.2f}%")
        
        print(f"\nMatriz de Confusión:")
        print(f"  {cm}")
        print(f"\n  TN: {cm[0,0]}, FP: {cm[0,1]}")
        print(f"  FN: {cm[1,0]}, TP: {cm[1,1]}")
        
        print(f"\nTiempos de Inferencia:")
        print(f"  - Promedio:  {avg_time:.2f} ms")
        print(f"  - Desv. Est: {std_time:.2f} ms")
        print(f"  - Mínimo:    {min_time:.2f} ms")
        print(f"  - Máximo:    {max_time:.2f} ms")
        print(f"  - FPS aprox: {1000/avg_time:.1f} frames/segundo" if avg_time > 0 else "  - FPS aprox: N/A")
        
    def generate_pdf_report(self, output_path: str = "informe_modelo_somnolencia.pdf"):
        """
        Genera un informe en PDF con los resultados
        
        Args:
            output_path: Ruta del archivo PDF de salida
        """
        print(f"\n5. Generando informe PDF: {output_path}")
        
        # Configurar estilo
        sns.set_style("whitegrid")
        plt.rcParams['figure.figsize'] = (11, 8.5)  # Tamaño carta
        
        with PdfPages(output_path) as pdf:
            # Página 1: Portada
            self._create_cover_page(pdf)
            
            # Página 2: Métricas generales
            self._create_metrics_page(pdf)
            
            # Página 3: Matriz de confusión y distribuciones
            self._create_confusion_matrix_page(pdf)
            
            # Página 4: Análisis de tiempos
            self._create_timing_analysis_page(pdf)
            
            # Página 5: Distribución de EAR
            self._create_ear_distribution_page(pdf)
            
        print(f"   ✓ Informe generado exitosamente: {output_path}")
    
    def _create_cover_page(self, pdf):
        """Crea página de portada"""
        fig = plt.figure(figsize=(8.5, 11))
        ax = fig.add_subplot(111)
        ax.axis('off')
        
        # Título
        title_text = "INFORME DE EVALUACIÓN\nMODELO DE DETECCIÓN DE SOMNOLENCIA"
        ax.text(0.5, 0.7, title_text, 
                ha='center', va='center', 
                fontsize=24, fontweight='bold',
                transform=ax.transAxes)
        
        # Información del modelo
        info_text = f"""
        Modelo: MediaPipe Face Landmarker
        Algoritmo: Eye Aspect Ratio (EAR)
        
        Fecha de evaluación: {datetime.now().strftime('%d/%m/%Y %H:%M')}
        
        Dataset: Driver Drowsiness Dataset (DDD)
        Total de muestras evaluadas: {len(self.results['true_labels'])}
        """
        
        ax.text(0.5, 0.4, info_text,
                ha='center', va='center',
                fontsize=12,
                transform=ax.transAxes,
                bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.3))
        
        # Footer
        footer_text = "Sistema de Detección de Microsueños\nUniversidad - Proyecto de Comunicaciones"
        ax.text(0.5, 0.1, footer_text,
                ha='center', va='center',
                fontsize=10, style='italic',
                transform=ax.transAxes)
        
        pdf.savefig(fig, bbox_inches='tight')
        plt.close()
    
    def _create_metrics_page(self, pdf):
        """Crea página con métricas principales"""
        fig, axes = plt.subplots(2, 2, figsize=(11, 8.5))
        fig.suptitle('Métricas de Rendimiento del Modelo', fontsize=16, fontweight='bold')
        
        metrics = self.results['metrics']
        
        # Gráfico 1: Métricas de clasificación (barras)
        ax1 = axes[0, 0]
        metric_names = ['Accuracy', 'Precision', 'Recall', 'F1-Score']
        metric_values = [
            metrics['accuracy'] * 100,
            metrics['precision'] * 100,
            metrics['recall'] * 100,
            metrics['f1_score'] * 100
        ]
        colors = ['#2ecc71', '#3498db', '#e74c3c', '#f39c12']
        bars = ax1.bar(metric_names, metric_values, color=colors, alpha=0.7)
        ax1.set_ylabel('Porcentaje (%)')
        ax1.set_title('Métricas de Clasificación')
        ax1.set_ylim([0, 105])
        ax1.grid(axis='y', alpha=0.3)
        
        # Añadir valores sobre las barras
        for bar in bars:
            height = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width()/2., height,
                    f'{height:.1f}%',
                    ha='center', va='bottom', fontweight='bold')
        
        # Gráfico 2: Resumen numérico
        ax2 = axes[0, 1]
        ax2.axis('off')
        
        summary_text = f"""
        RESUMEN DE MÉTRICAS
        {'='*35}
        
        Accuracy:        {metrics['accuracy']*100:.2f}%
        Precision:       {metrics['precision']*100:.2f}%
        Recall:          {metrics['recall']*100:.2f}%
        F1-Score:        {metrics['f1_score']*100:.2f}%
        
        {'='*35}
        
        Tiempo de Inferencia:
        
        Promedio:        {metrics['avg_inference_time_ms']:.2f} ms
        Desv. Estándar:  {metrics['std_inference_time_ms']:.2f} ms
        Mínimo:          {metrics['min_inference_time_ms']:.2f} ms
        Máximo:          {metrics['max_inference_time_ms']:.2f} ms
        
        FPS estimado:    {1000/metrics['avg_inference_time_ms']:.1f} fps
        """
        
        ax2.text(0.1, 0.5, summary_text,
                fontsize=10, family='monospace',
                va='center', transform=ax2.transAxes,
                bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.3))
        
        # Gráfico 3: Distribución de predicciones
        ax3 = axes[1, 0]
        y_true = np.array(self.results['true_labels'])
        y_pred = np.array(self.results['predicted_labels'])
        
        categories = ['Non Drowsy', 'Drowsy']
        true_counts = [np.sum(y_true == 0), np.sum(y_true == 1)]
        pred_counts = [np.sum(y_pred == 0), np.sum(y_pred == 1)]
        
        x = np.arange(len(categories))
        width = 0.35
        
        ax3.bar(x - width/2, true_counts, width, label='Real', color='#3498db', alpha=0.7)
        ax3.bar(x + width/2, pred_counts, width, label='Predicho', color='#e74c3c', alpha=0.7)
        
        ax3.set_xlabel('Clase')
        ax3.set_ylabel('Cantidad de muestras')
        ax3.set_title('Distribución de Clases: Real vs Predicho')
        ax3.set_xticks(x)
        ax3.set_xticklabels(categories)
        ax3.legend()
        ax3.grid(axis='y', alpha=0.3)
        
        # Gráfico 4: Interpretación de resultados
        ax4 = axes[1, 1]
        ax4.axis('off')
        
        cm = metrics['confusion_matrix']
        tp, fp, fn, tn = cm[1,1], cm[0,1], cm[1,0], cm[0,0]
        
        interpretation_text = f"""
        INTERPRETACIÓN DE RESULTADOS
        {'='*35}
        
        Verdaderos Positivos (TP): {tp}
        Predicciones correctas de somnolencia
        
        Verdaderos Negativos (TN): {tn}
        Predicciones correctas de alerta
        
        Falsos Positivos (FP): {fp}
        Alertas incorrectas de somnolencia
        
        Falsos Negativos (FN): {fn}
        Casos de somnolencia no detectados
        
        {'='*35}
        
        Tasa de Acierto: {metrics['accuracy']*100:.1f}%
        """
        
        ax4.text(0.1, 0.5, interpretation_text,
                fontsize=9, family='monospace',
                va='center', transform=ax4.transAxes,
                bbox=dict(boxstyle='round', facecolor='lightgreen', alpha=0.2))
        
        plt.tight_layout()
        pdf.savefig(fig, bbox_inches='tight')
        plt.close()
    
    def _create_confusion_matrix_page(self, pdf):
        """Crea página con matriz de confusión"""
        fig, axes = plt.subplots(1, 2, figsize=(11, 8.5))
        fig.suptitle('Análisis de Matriz de Confusión', fontsize=16, fontweight='bold')
        
        cm = self.results['metrics']['confusion_matrix']
        
        # Matriz de confusión absoluta
        ax1 = axes[0]
        sns.heatmap(cm, annot=True, fmt='d', cmap='Blues', ax=ax1,
                   xticklabels=['Non Drowsy', 'Drowsy'],
                   yticklabels=['Non Drowsy', 'Drowsy'],
                   cbar_kws={'label': 'Cantidad'})
        ax1.set_ylabel('Etiqueta Real')
        ax1.set_xlabel('Predicción del Modelo')
        ax1.set_title('Matriz de Confusión (Valores Absolutos)')
        
        # Matriz de confusión normalizada
        ax2 = axes[1]
        cm_normalized = cm.astype('float') / cm.sum(axis=1)[:, np.newaxis]
        sns.heatmap(cm_normalized, annot=True, fmt='.2%', cmap='Greens', ax=ax2,
                   xticklabels=['Non Drowsy', 'Drowsy'],
                   yticklabels=['Non Drowsy', 'Drowsy'],
                   cbar_kws={'label': 'Proporción'})
        ax2.set_ylabel('Etiqueta Real')
        ax2.set_xlabel('Predicción del Modelo')
        ax2.set_title('Matriz de Confusión (Normalizada)')
        
        plt.tight_layout()
        pdf.savefig(fig, bbox_inches='tight')
        plt.close()
    
    def _create_timing_analysis_page(self, pdf):
        """Crea página con análisis de tiempos"""
        if not self.results['inference_times']:
            return
            
        fig, axes = plt.subplots(2, 2, figsize=(11, 8.5))
        fig.suptitle('Análisis de Tiempos de Inferencia', fontsize=16, fontweight='bold')
        
        times = np.array(self.results['inference_times'])
        
        # Histograma
        ax1 = axes[0, 0]
        ax1.hist(times, bins=30, color='skyblue', edgecolor='black', alpha=0.7)
        ax1.axvline(np.mean(times), color='red', linestyle='--', linewidth=2, label=f'Media: {np.mean(times):.2f} ms')
        ax1.set_xlabel('Tiempo de Inferencia (ms)')
        ax1.set_ylabel('Frecuencia')
        ax1.set_title('Distribución de Tiempos de Inferencia')
        ax1.legend()
        ax1.grid(alpha=0.3)
        
        # Box plot
        ax2 = axes[0, 1]
        ax2.boxplot(times, vert=True, patch_artist=True,
                   boxprops=dict(facecolor='lightgreen', alpha=0.7),
                   medianprops=dict(color='red', linewidth=2))
        ax2.set_ylabel('Tiempo de Inferencia (ms)')
        ax2.set_title('Box Plot de Tiempos')
        ax2.grid(axis='y', alpha=0.3)
        
        # Serie temporal
        ax3 = axes[1, 0]
        ax3.plot(times, color='purple', alpha=0.5, linewidth=0.5)
        ax3.axhline(np.mean(times), color='red', linestyle='--', linewidth=2, label='Media')
        ax3.set_xlabel('Número de Muestra')
        ax3.set_ylabel('Tiempo de Inferencia (ms)')
        ax3.set_title('Evolución Temporal de Inferencias')
        ax3.legend()
        ax3.grid(alpha=0.3)
        
        # Estadísticas
        ax4 = axes[1, 1]
        ax4.axis('off')
        
        percentiles = np.percentile(times, [25, 50, 75, 90, 95, 99])
        
        stats_text = f"""
        ESTADÍSTICAS DE TIEMPO
        {'='*40}
        
        Media:              {np.mean(times):.2f} ms
        Mediana:            {np.median(times):.2f} ms
        Desviación Est.:    {np.std(times):.2f} ms
        
        Mínimo:             {np.min(times):.2f} ms
        Máximo:             {np.max(times):.2f} ms
        
        Percentiles:
          P25:              {percentiles[0]:.2f} ms
          P50 (Mediana):    {percentiles[1]:.2f} ms
          P75:              {percentiles[2]:.2f} ms
          P90:              {percentiles[3]:.2f} ms
          P95:              {percentiles[4]:.2f} ms
          P99:              {percentiles[5]:.2f} ms
        
        {'='*40}
        
        Frames por segundo (FPS):
          Promedio:         {1000/np.mean(times):.1f} fps
          Mínimo:           {1000/np.max(times):.1f} fps
          Máximo:           {1000/np.min(times):.1f} fps
        """
        
        ax4.text(0.1, 0.5, stats_text,
                fontsize=9, family='monospace',
                va='center', transform=ax4.transAxes,
                bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.3))
        
        plt.tight_layout()
        pdf.savefig(fig, bbox_inches='tight')
        plt.close()
    
    def _create_ear_distribution_page(self, pdf):
        """Crea página con distribución de valores EAR"""
        if not self.results['ear_values']:
            return
            
        fig, axes = plt.subplots(2, 2, figsize=(11, 8.5))
        fig.suptitle('Análisis de Eye Aspect Ratio (EAR)', fontsize=16, fontweight='bold')
        
        ear_values = np.array(self.results['ear_values'])
        y_true = np.array(self.results['true_labels'])
        
        # Separar por clase
        ear_drowsy = ear_values[y_true == 1]
        ear_alert = ear_values[y_true == 0]
        
        # Histograma comparativo
        ax1 = axes[0, 0]
        ax1.hist(ear_alert, bins=30, alpha=0.6, label='Non Drowsy', color='green', edgecolor='black')
        ax1.hist(ear_drowsy, bins=30, alpha=0.6, label='Drowsy', color='red', edgecolor='black')
        ax1.axvline(0.10, color='blue', linestyle='--', linewidth=2, label='Umbral (0.10)')
        ax1.set_xlabel('Valor EAR')
        ax1.set_ylabel('Frecuencia')
        ax1.set_title('Distribución de EAR por Clase')
        ax1.legend()
        ax1.grid(alpha=0.3)
        
        # Box plot comparativo
        ax2 = axes[0, 1]
        data_to_plot = [ear_alert, ear_drowsy]
        bp = ax2.boxplot(data_to_plot, labels=['Non Drowsy', 'Drowsy'], patch_artist=True)
        bp['boxes'][0].set_facecolor('green')
        bp['boxes'][1].set_facecolor('red')
        for box in bp['boxes']:
            box.set_alpha(0.5)
        ax2.axhline(0.10, color='blue', linestyle='--', linewidth=2, label='Umbral')
        ax2.set_ylabel('Valor EAR')
        ax2.set_title('Comparación de EAR entre Clases')
        ax2.legend()
        ax2.grid(axis='y', alpha=0.3)
        
        # Densidad de probabilidad
        ax3 = axes[1, 0]
        if len(ear_alert) > 0:
            ax3.hist(ear_alert, bins=50, density=True, alpha=0.6, label='Non Drowsy', color='green')
        if len(ear_drowsy) > 0:
            ax3.hist(ear_drowsy, bins=50, density=True, alpha=0.6, label='Drowsy', color='red')
        ax3.axvline(0.10, color='blue', linestyle='--', linewidth=2, label='Umbral')
        ax3.set_xlabel('Valor EAR')
        ax3.set_ylabel('Densidad')
        ax3.set_title('Densidad de Probabilidad de EAR')
        ax3.legend()
        ax3.grid(alpha=0.3)
        
        # Estadísticas
        ax4 = axes[1, 1]
        ax4.axis('off')
        
        stats_text = f"""
        ESTADÍSTICAS DE EAR
        {'='*40}
        
        Non Drowsy (Alerta):
          Media:            {np.mean(ear_alert):.4f}
          Mediana:          {np.median(ear_alert):.4f}
          Desv. Est.:       {np.std(ear_alert):.4f}
          Mínimo:           {np.min(ear_alert):.4f}
          Máximo:           {np.max(ear_alert):.4f}
          Muestras:         {len(ear_alert)}
        
        Drowsy (Somnoliento):
          Media:            {np.mean(ear_drowsy):.4f}
          Mediana:          {np.median(ear_drowsy):.4f}
          Desv. Est.:       {np.std(ear_drowsy):.4f}
          Mínimo:           {np.min(ear_drowsy):.4f}
          Máximo:           {np.max(ear_drowsy):.4f}
          Muestras:         {len(ear_drowsy)}
        
        {'='*40}
        
        Umbral de Detección: 0.10
        
        Separabilidad:      {abs(np.mean(ear_alert) - np.mean(ear_drowsy)):.4f}
        """
        
        ax4.text(0.1, 0.5, stats_text,
                fontsize=9, family='monospace',
                va='center', transform=ax4.transAxes,
                bbox=dict(boxstyle='round', facecolor='lightcyan', alpha=0.3))
        
        plt.tight_layout()
        pdf.savefig(fig, bbox_inches='tight')
        plt.close()


def main():
    # Relative path from the proyect root
    DATASET_PATH = "dataset/Driver Drowsiness Dataset (DDD)"
    MODEL_PATH = "microsleeps_detector/app/src/main/assets/face_landmarker.task"
    OUTPUT_PDF = "testing/informe_modelo_somnolencia.pdf"
    
    MAX_SAMPLES = 10000  # @_@
    
    print("\n" + "="*60)
    print("EVALUACIÓN DEL MODELO DE DETECCIÓN DE SOMNOLENCIA")
    print("="*60)
    print(f"\nConfiguración:")
    print(f"  - Dataset: {DATASET_PATH}")
    print(f"  - Modelo: {MODEL_PATH}")
    print(f"  - Muestras por clase: {MAX_SAMPLES}")
    print(f"  - Informe PDF: {OUTPUT_PDF}")
    
    # Check paths
    if not Path(DATASET_PATH).exists():
        print(f"\n❌ Error: No se encuentra el dataset en {DATASET_PATH}")
        return
    
    if not Path(MODEL_PATH).exists():
        print(f"\n❌ Error: No se encuentra el modelo en {MODEL_PATH}")
        return
    
    evaluator = ModelEvaluator(DATASET_PATH, MODEL_PATH)
    
    try:
        evaluator.evaluate(max_samples_per_class=MAX_SAMPLES, show_progress=100)
        evaluator.generate_pdf_report(OUTPUT_PDF)
        
        print("\n" + "="*60)
        print("✓ EVALUACIÓN COMPLETADA EXITOSAMENTE")
        print("="*60)
        print(f"Informe generado: {OUTPUT_PDF}")
        
    except Exception as e:
        print(f"\n❌ Error durante la evaluación: {str(e)}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
