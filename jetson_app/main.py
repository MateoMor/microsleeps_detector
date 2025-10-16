from camera.camera_handler import CameraHandler

class App:
    def run(self):
        camera = CameraHandler()
        camera.run()


if __name__ == "__main__":
    app = App()
    app.run()