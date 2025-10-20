"""
Módulo para análisis PERCLOS (Percentage of Eye Closure)
Calcula el porcentaje de tiempo que los ojos permanecen cerrados
"""

from collections import deque
import time

class PERCLOSAnalyzer:
    def __init__(self, window_seconds=30, ear_threshold=0.21):
        """
        Inicializa el analizador PERCLOS
        
        Args:
            window_seconds: Ventana de tiempo para análisis (segundos)
            ear_threshold: Umbral de EAR para considerar ojo cerrado
        """
        self.window_seconds = window_seconds
        self.ear_threshold = ear_threshold
        
        # Cola para almacenar tuplas (timestamp, ear_value)
        self.ear_history = deque()
        
        # Umbrales para detección
        self.perclos_warning_threshold = 0.15  # 15% - Somnolencia
        self.perclos_danger_threshold = 0.40   # 40% - Microsueño
        
        # Estado actual
        self.current_perclos = 0.0
        self.current_state = "Atento"  # "Atento", "Somnoliento", "Microsueño"
    
    def add_ear_measurement(self, ear_value):
        """
        Agrega una nueva medición de EAR
        
        Args:
            ear_value: Valor del Eye Aspect Ratio
        """
        current_time = time.time()
        self.ear_history.append((current_time, ear_value))
        
        # Limpiar mediciones antiguas (fuera de la ventana de tiempo)
        cutoff_time = current_time - self.window_seconds
        while self.ear_history and self.ear_history[0][0] < cutoff_time:
            self.ear_history.popleft()
    
    def calculate_perclos(self):
        """
        Calcula el PERCLOS actual
        
        Returns:
            float: Porcentaje de cierre de ojos (0.0 a 1.0)
        """
        if len(self.ear_history) < 2:
            return 0.0
        
        # Contar frames con ojos cerrados
        closed_count = sum(1 for _, ear in self.ear_history if ear < self.ear_threshold)
        total_count = len(self.ear_history)
        
        self.current_perclos = closed_count / total_count if total_count > 0 else 0.0
        
        # Actualizar estado basado en PERCLOS
        self._update_state()
        
        return self.current_perclos
    
    def _update_state(self):
        """Actualiza el estado del conductor basado en PERCLOS"""
        if self.current_perclos >= self.perclos_danger_threshold:
            self.current_state = "Microsueño"
        elif self.current_perclos >= self.perclos_warning_threshold:
            self.current_state = "Somnoliento"
        else:
            self.current_state = "Atento"
    
    def get_state(self):
        """
        Obtiene el estado actual del conductor
        
        Returns:
            dict: Información del estado actual
        """
        return {
            "state": self.current_state,
            "perclos": self.current_perclos,
            "perclos_percentage": self.current_perclos * 100,
            "is_warning": self.current_perclos >= self.perclos_warning_threshold,
            "is_danger": self.current_perclos >= self.perclos_danger_threshold,
            "samples_in_window": len(self.ear_history),
            "window_seconds": self.window_seconds
        }
    
    def reset(self):
        """Reinicia el historial de mediciones"""
        self.ear_history.clear()
        self.current_perclos = 0.0
        self.current_state = "Atento"