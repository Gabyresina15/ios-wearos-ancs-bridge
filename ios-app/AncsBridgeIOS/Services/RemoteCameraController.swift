import AVFoundation
import Photos
import SwiftUI
import UIKit

@MainActor
final class RemoteCameraController: NSObject, ObservableObject {
    static let shared = RemoteCameraController()

    @Published var isPresented: Bool = false
    @Published var statusMessage: String?
    @Published var authorizationDenied: Bool = false

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.ancsbridge.ios.camera.session")
    private let photoOutput = AVCapturePhotoOutput()
    private var isConfigured = false
    private var captureCompletion: ((Bool) -> Void)?
    private var pendingCapture = false

    private override init() {
        super.init()
    }

    var isAppActive: Bool {
        UIApplication.shared.applicationState == .active
    }

    func handleWatchOpenRequest() -> String {
        guard isAppActive else {
            return "need_foreground"
        }
        presentAndStart()
        return "ready"
    }

    func handleWatchCaptureRequest() -> String {
        guard isAppActive else {
            return "need_foreground"
        }
        if session.isRunning {
            capturePhoto()
            return "saving"
        }
        pendingCapture = true
        presentAndStart()
        return "saving"
    }

    func presentAndStart() {
        isPresented = true
        statusMessage = nil
        Task { await prepareSessionIfNeeded() }
    }

    func dismiss() {
        isPresented = false
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func capturePhoto(completion: ((Bool) -> Void)? = nil) {
        captureCompletion = completion
        sessionQueue.async { [weak self] in
            guard let self else { return }
            let settings = AVCapturePhotoSettings()
            if self.photoOutput.connection(with: .video) == nil {
                DispatchQueue.main.async {
                    self.statusMessage = "La cámara no está lista"
                    completion?(false)
                }
                return
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    private func prepareSessionIfNeeded() async {
        let cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
        let granted: Bool
        if cameraStatus == .authorized {
            granted = true
        } else if cameraStatus == .notDetermined {
            granted = await AVCaptureDevice.requestAccess(for: .video)
        } else {
            granted = false
        }
        if !granted {
            authorizationDenied = true
            statusMessage = "Permití el acceso a la cámara"
            WatchBleCentralManager.shared.sendCameraStatus("denied")
            return
        }

        let photoStatus = await requestPhotoLibraryAccess()
        if !(photoStatus == .authorized || photoStatus == .limited) {
            authorizationDenied = true
            statusMessage = "Permití guardar fotos en el carrete"
            WatchBleCentralManager.shared.sendCameraStatus("denied")
            return
        }

        authorizationDenied = false
        sessionQueue.async { [weak self] in
            self?.configureSessionIfNeeded()
            if let self, !self.session.isRunning {
                self.session.startRunning()
            }
            DispatchQueue.main.async {
                WatchBleCentralManager.shared.sendCameraStatus("ready")
                if self.pendingCapture {
                    self.pendingCapture = false
                    self.capturePhoto()
                }
            }
        }
    }

    private func requestPhotoLibraryAccess() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current == .notDetermined {
            return await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        }
        return current
    }

    private func configureSessionIfNeeded() {
        if isConfigured { return }
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            DispatchQueue.main.async {
                self.statusMessage = "No se encontró la cámara"
                WatchBleCentralManager.shared.sendCameraStatus("error")
            }
            return
        }
        session.addInput(input)
        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
        }
        session.commitConfiguration()
        isConfigured = true
    }

    private func savePhotoData(_ data: Data) {
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, data: data, options: nil)
        }, completionHandler: { [weak self] success, error in
            DispatchQueue.main.async {
                if success {
                    self?.statusMessage = "Foto guardada en el carrete"
                    WatchBleCentralManager.shared.sendCameraStatus("captured")
                } else {
                    self?.statusMessage = error?.localizedDescription ?? "No se pudo guardar la foto"
                    WatchBleCentralManager.shared.sendCameraStatus("error")
                }
                self?.captureCompletion?(success)
                self?.captureCompletion = nil
            }
        })
    }
}

extension RemoteCameraController: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error {
            Task { @MainActor in
                self.statusMessage = error.localizedDescription
                WatchBleCentralManager.shared.sendCameraStatus("error")
                self.captureCompletion?(false)
                self.captureCompletion = nil
            }
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            Task { @MainActor in
                self.statusMessage = "No se pudo leer la foto"
                WatchBleCentralManager.shared.sendCameraStatus("error")
                self.captureCompletion?(false)
                self.captureCompletion = nil
            }
            return
        }
        Task { @MainActor in
            self.savePhotoData(data)
        }
    }
}

struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {
        uiView.previewLayer.session = session
    }

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

struct RemoteCameraScreen: View {
    @ObservedObject var camera: RemoteCameraController = .shared

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreviewView(session: camera.session)
                .ignoresSafeArea()

            VStack {
                HStack {
                    Button("Cerrar") { camera.dismiss() }
                        .foregroundStyle(.white)
                        .padding()
                    Spacer()
                }
                Spacer()
                if let message = camera.statusMessage {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(.black.opacity(0.45))
                        .clipShape(Capsule())
                }
                Button {
                    camera.capturePhoto()
                } label: {
                    Circle()
                        .fill(Color.white)
                        .frame(width: 72, height: 72)
                        .overlay(Circle().stroke(Color.white.opacity(0.4), lineWidth: 6))
                }
                .padding(.bottom, 36)
            }
        }
        .statusBarHidden()
    }
}
