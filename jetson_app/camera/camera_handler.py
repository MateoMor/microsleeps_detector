import cv2
import mediapipe as mp
import numpy as np
from datetime import datetime
import time

from .face_parser import FaceParser

class CameraHandler:
    def __init__ (self):
        # Fps vars
        self.fps_counter = 0
        self.fps_start_time = time.time()
        self.fps_actual = 0
        
        # Resolution of processing (lower = faster)
        self.process_width = 640 

    def calculate_fps(self):
        """ Calcule FPS """
        self.fps_counter += 1
        if time.time() - self.fps_start_time >= 1.0:
            self.fps_actual = self.fps_counter
            self.fps_counter = 0
            self.fps_start_time = time.time()


    def run(self):
        """Process the real-time video OPTIMIZED"""
        cap = cv2.VideoCapture(0)
        
        if not cap.isOpened():
            print("Error: No se puede abrir la cámara")
            return
        
        # Configurar resolución de captura (opcional)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
        cap.set(cv2.CAP_PROP_FPS, 30)
        
        print("\n=== ANALIZADOR FACIAL ===")
        print("Controles:")
        print("  'q' - Salir")
        print("  's' - Mostrar/ocultar FPS")
        print("=====================================\n")
        
        show_fps = True

        face_parser = FaceParser()


        while cap.isOpened():
            success, frame = cap.read()
            if not success:
                print("Error al leer frame")
                break
            
            # This is for front camera, flip the frame
            frame = cv2.flip(frame, 1)
            
            # OPTIMIZATION: Reduce resolution for processing
            height, width = frame.shape[:2]
            process_height = int(height * (self.process_width / width))
            small_frame = cv2.resize(frame, (self.process_width, process_height))
            rgb_frame = cv2.cvtColor(small_frame, cv2.COLOR_BGR2RGB)


            process_frame = face_parser.process_frame(rgb_frame, height, width)

            self.calculate_fps()


            # Show FPS
            if show_fps:
                cv2.putText(process_frame, f"FPS: {self.fps_actual}", (process_frame.shape[1] - 120, 30), 
                           cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
            
            
            process_frame = cv2.cvtColor(process_frame, cv2.COLOR_RGB2BGR)
            
            # Show frames
            cv2.imshow('Analizador Facial - Seguimiento de Ojos [OPTIMIZADO]', process_frame)
            
            
            # Handle keys
            key = cv2.waitKey(1) & 0xFF
            if key == ord('q'):
                break
            elif key == ord('f'):
                face_parser.face_bool()
            elif key == ord('s'):
                show_fps = not show_fps
        
        
        cap.release()
        cv2.destroyAllWindows()
        
    
    