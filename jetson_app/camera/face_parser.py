import cv2
import mediapipe as mp
import numpy as np

class FaceParser:
    def __init__(self):
        # Inicializar MediaPipe Face Mesh con optimizaciones
        self.mp_face_mesh = mp.solutions.face_mesh
        
        self.face_mesh = self.mp_face_mesh.FaceMesh(
            max_num_faces=1,
            refine_landmarks=True,  # NECESARIO para detectar iris
            min_detection_confidence=0.3,  # Más bajo = más rápido
            min_tracking_confidence=0.3,   # Más bajo = más rápido
            static_image_mode=False  # Optimizado para video
        )

        # Felipe: What those do?
        self.mp_drawing = mp.solutions.drawing_utils
        self.mp_drawing_styles = mp.solutions.drawing_styles
        
        # Índices CORRECTOS de los puntos clave para los ojos
        self.LEFT_EYE = [33, 160, 158, 133, 153, 144]
        self.RIGHT_EYE = [362, 385, 387, 263, 373, 380]
        
        
        # DEBUG
        self.mostrar_mesh_completo = False

        self.panel = Panel2D()
        
    def eye_aspect_ratio(self, landmarks, indices, w, h, img=None, color=(0,255,0)):
        pts = [(int(landmarks[i].x * w), int(landmarks[i].y * h)) for i in indices]
        p0, p1, p2, p3, p4, p5 = np.array(pts)

        # Distancias
        A = np.linalg.norm(p1 - p5)
        B = np.linalg.norm(p2 - p4)
        C = np.linalg.norm(p0 - p3)
        ear = (A + B) / C

        # Dibujar si hay imagen
        if img is not None:
            # Puntos
            for p in pts:
                cv2.circle(img, p, 3, color, -1)

            # Líneas A, B y C (para referencia)
            cv2.line(img, tuple(p1), tuple(p5), (255,0,0), 1)  # línea A (azul)
            cv2.line(img, tuple(p2), tuple(p4), (255,0,0), 1)  # línea B (azul)
            cv2.line(img, tuple(p0), tuple(p3), (0,0,255), 1)  # línea C (roja)

        return ear

    def dibujar_ojos(self, frame, landmarks):
        """Dibuja los ojos y el iris"""
        h, w = frame.shape[:2]
        
        # Dibujar ojo izquierdo
        left_eye_pts = np.array([(int(landmarks[p].x * w), int(landmarks[p].y * h)) 
                                  for p in self.LEFT_EYE], dtype=np.int32)
        cv2.polylines(frame, [left_eye_pts], True, (0, 255, 0), 1)
        
        # Dibujar ojo derecho
        right_eye_pts = np.array([(int(landmarks[p].x * w), int(landmarks[p].y * h)) 
                                   for p in self.RIGHT_EYE], dtype=np.int32)
        cv2.polylines(frame, [right_eye_pts], True, (0, 255, 0), 1)
        
        return frame

    def face_bool(self):
        self.mostrar_mesh_completo = not self.mostrar_mesh_completo

    #Esto aun no funciona bien
    def calcular_cabeceo(self, landmarks, w, h):
        
        # Puntos 3D de referencia (modelo facial promedio)
        model_points = np.array([
            (0.0, 0.0, 0.0),          # Nariz
            (0.0, -330.0, -65.0),     # Barbilla
            (-225.0, 170.0, -135.0),  # Ojo izquierdo
            (225.0, 170.0, -135.0),   # Ojo derecho
            (-150.0, -150.0, -125.0), # Boca izquierda
            (150.0, -150.0, -125.0)   # Boca derecha
        ])

        # Landmarks 2D de la cara actual
        image_points = np.array([
            (landmarks[1].x * w, landmarks[1].y * h),     # Nariz
            (landmarks[152].x * w, landmarks[152].y * h), # Barbilla
            (landmarks[33].x * w, landmarks[33].y * h),   # Ojo izquierdo
            (landmarks[263].x * w, landmarks[263].y * h), # Ojo derecho
            (landmarks[61].x * w, landmarks[61].y * h),   # Boca izquierda
            (landmarks[291].x * w, landmarks[291].y * h), # Boca derecha
        ], dtype=np.float64)

        # Parámetros de cámara (asumiendo cámara simple)
        focal_length = w
        center = (w / 2, h / 2)
        camera_matrix = np.array([
            [focal_length, 0, center[0]],
            [0, focal_length, center[1]],
            [0, 0, 1]
        ], dtype=np.float64)

        dist_coeffs = np.zeros((4,1))  # sin distorsión

        success, rotation_vector, translation_vector = cv2.solvePnP(
            model_points, image_points, camera_matrix, dist_coeffs
        )

        if not success:
            return 0.0, 0.0, 0.0

        # Convertir a ángulos de Euler
        rmat, _ = cv2.Rodrigues(rotation_vector)
        angles, _, _, _, _, _ = cv2.RQDecomp3x3(rmat)

        pitch, yaw, roll = angles * np.array([180, 180, 180])  # en grados

        return pitch, yaw, roll


    def process_frame(self, frame, height, width):
        """Process a single frame and return annotated frame"""
        # Procesar con MediaPipe
        results = self.face_mesh.process(frame)
        panel_2d = None
        
        # Procesar resultados
        if results.multi_face_landmarks:
            for face_landmarks in results.multi_face_landmarks:
                # SOLO dibujar mesh completo si está activado
                if self.mostrar_mesh_completo:
                    # Escalar landmarks al frame original
                    scaled_landmarks = self.mp_face_mesh.FaceMesh()
                    self.mp_drawing.draw_landmarks(
                        image=frame,
                        landmark_list=face_landmarks,
                        connections=self.mp_face_mesh.FACEMESH_TESSELATION,
                        landmark_drawing_spec=None,
                        connection_drawing_spec=self.mp_drawing_styles
                            .get_default_face_mesh_tesselation_style()
                    )
                # Dibujar ojos
                frame = self.dibujar_ojos(frame, face_landmarks.landmark)

                # Calcular y dibujar EAR
                lm = results.multi_face_landmarks[0].landmark

                ear_l = self.eye_aspect_ratio(lm, self.LEFT_EYE, width, height, frame, color=(0,255,0))
                ear_r = self.eye_aspect_ratio(lm, self.RIGHT_EYE, width, height, frame, color=(0,255,255))
                ear = (ear_l + ear_r) / 2.0

                pitch, yaw, roll = self.calcular_cabeceo(face_landmarks.landmark, width, height)

                # Dibujar valores en pantalla
                cv2.putText(frame, f"Pitch: {pitch:.2f}", (30, 40),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0,255,0), 2)
                cv2.putText(frame, f"Yaw: {yaw:.2f}", (30, 70),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255,255,0), 2)
                cv2.putText(frame, f"Roll: {roll:.2f}", (30, 100),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0,255,255), 2)

                # Detectar posible cabeceo (cabeza bajando)
                if pitch > 30:  # ajusta este umbral según tus pruebas
                    cv2.putText(frame, "Cabeceo detectado!", (200, 200), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0,0,255), 3)
                
                # Crear panel 2D con valores de EAR
                panel_2d = self.panel.dibujar_panel_2d(self, face_landmarks.landmark, ear_l, ear_r)
                
                
        if panel_2d is not None:
            cv2.imshow('Panel 2D - Vista Ojos', panel_2d)
            
        return frame
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
        
class Panel2D:
    # Panel de visualización 2D
    def __init__(self):
        self.panel_height = 300
        self.panel_width = 800
        self.scale = 3000

    def _dibujar_cuadricula(self, panel):
        """Dibuja una cuadrícula de referencia en el panel"""
        grid_color = (40, 40, 40)
        grid_spacing = 20  # Espaciado más fino para mejor referencia
        
        # Líneas verticales
        for x in range(0, self.panel_width, grid_spacing):
            cv2.line(panel, (x, 0), (x, self.panel_height), grid_color, 1)

        # Líneas horizontales
        for y in range(0, self.panel_height, grid_spacing):
            cv2.line(panel, (0, y), (self.panel_width, y), grid_color, 1)
        
        # Línea divisoria central
        cv2.line(panel, (self.panel_width//2, 0), (self.panel_width//2, self.panel_height), (80, 80, 80), 2)

        # Título más compacto
        cv2.putText(panel, "VISTA 2D - TRACKING OCULAR", (self.panel_width//2 - 170, 20), 
                   cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
    
    def _dibujar_ojo(self, panel, eye_coords, offset):
        """Dibuja un ojo en el panel 2D"""

        # Centrar y escalar las coordenadas del ojo
        eye_center = eye_coords.mean(axis=0)
        eye_scaled = ((eye_coords - eye_center) * self.scale + offset).astype(int)
        
        # Dibujar contorno del ojo
        cv2.polylines(panel, [eye_scaled], True, (0, 255, 0), 2)
        cv2.fillPoly(panel, [eye_scaled], (0, 40, 0))  # Relleno semi-transparente

    def _dibujar_centro_referencia(self, panel, position):
        """Dibuja un punto de referencia en el centro del ojo"""
        cv2.circle(panel, tuple(position), 5, (0, 255, 255), -1)

    def _ojo_label(self, panel, position, text):
        """Dibuja una etiqueta para el ojo"""
        cv2.putText(panel, text, position, cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)

    def _dibujar_ear_visual(self, panel, eye_coords, offset, ear_value, lado=""):
        """Dibuja las líneas de EAR y su valor en el panel 2D"""
        # Centrar y escalar las coordenadas del ojo
        eye_center = eye_coords.mean(axis=0)
        eye_scaled = ((eye_coords - eye_center) * self.scale + offset).astype(int)
        
        # Obtener los puntos (p0, p1, p2, p3, p4, p5)
        p0, p1, p2, p3, p4, p5 = eye_scaled
        
        # Dibujar línea A (p1 - p5) en azul
        cv2.line(panel, tuple(p1), tuple(p5), (255, 0, 0), 2)
        
        # Dibujar línea B (p2 - p4) en azul
        cv2.line(panel, tuple(p2), tuple(p4), (255, 0, 0), 2)
        
        # Dibujar línea C (p0 - p3) en rojo
        cv2.line(panel, tuple(p0), tuple(p3), (0, 0, 255), 2)
        
        # Dibujar los puntos
        for p in eye_scaled:
            cv2.circle(panel, tuple(p), 3, (0, 255, 255), -1)
        
        # Mostrar valor de EAR
        text_pos = (int(offset[0] - 50), int(offset[1] + 80))
        cv2.putText(panel, f"EAR: {ear_value:.3f}", text_pos, 
                   cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 0), 2)

    def dibujar_panel_2d(self, orig, landmarks, ear_left=None, ear_right=None):
        """Crea un panel 2D con los ojos en cuadrícula ESCALADOS"""
        # Crear panel negro
        panel = np.zeros((self.panel_height, self.panel_width, 3), dtype=np.uint8)
        
        # Dibujar cuadrícula
        self._dibujar_cuadricula(panel)
        
        # Obtener coordenadas normalizadas de los ojos
        left_eye_coords = np.array([(landmarks[p].x, landmarks[p].y) for p in orig.LEFT_EYE])
        right_eye_coords = np.array([(landmarks[p].x, landmarks[p].y) for p in orig.RIGHT_EYE])

        # Posiciones centradas en cada mitad del panel
        offset_left = np.array([200, 160])   # Centro ojo izquierdo
        offset_right = np.array([600, 160])  # Centro ojo derecho
   

        self._dibujar_ojo(panel, left_eye_coords, offset_left)
        self._dibujar_ojo(panel, right_eye_coords, offset_right)

        self._dibujar_centro_referencia(panel, offset_left)
        self._dibujar_centro_referencia(panel, offset_right)
        
        # Dibujar visualización de EAR si se proporcionan los valores
        if ear_left is not None:
            self._dibujar_ear_visual(panel, left_eye_coords, offset_left, ear_left, "IZQ")
        if ear_right is not None:
            self._dibujar_ear_visual(panel, right_eye_coords, offset_right, ear_right, "DER")
      
        self._ojo_label(panel, (80, self.panel_height - 10), "OJO IZQUIERDO")
        self._ojo_label(panel, (490, self.panel_height - 10), "OJO DERECHO")

  
        return panel

