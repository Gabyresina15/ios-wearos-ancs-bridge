import Foundation
import AVFoundation
import UIKit
import UserNotifications

@MainActor
final class PhoneFindMyAlarmManager: ObservableObject {
    static let shared = PhoneFindMyAlarmManager()

    @Published var isRinging: Bool = false

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var torchTimer: Timer?
    private var autoStopTask: Task<Void, Never>?

    private init() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func startAlarm() {
        triggerFindPhoneAlarm()
    }

    func triggerFindPhoneAlarm() {
        if isRinging {
            stopAlarm()
            return
        }
        isRinging = true

        postLocalFindPhoneNotification()
        if UIApplication.shared.applicationState == .active {
            startSynthesizedSiren()
            startTorchStrobe()
        }

        autoStopTask?.cancel()
        autoStopTask = Task {
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            stopAlarm()
        }
    }

    func stopAlarm() {
        isRinging = false
        autoStopTask?.cancel()
        autoStopTask = nil

        torchTimer?.invalidate()
        torchTimer = nil
        setTorch(on: false)

        playerNode?.stop()
        audioEngine?.stop()
        playerNode = nil
        audioEngine = nil

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func startSynthesizedSiren() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.overrideOutputAudioPort(.speaker)
            try session.setActive(true)

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)

            let sampleRate = 44100.0
            let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
            engine.connect(player, to: engine.mainMixerNode, format: format)

            let durationSeconds = 0.6
            let frameCount = AVAudioFrameCount(sampleRate * durationSeconds)
            if let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) {
                buffer.frameLength = frameCount
                let channels = buffer.floatChannelData![0]
                for i in 0..<Int(frameCount) {
                    let t = Double(i) / sampleRate
                    let freq = (t < 0.3) ? 880.0 : 1320.0
                    channels[i] = Float(sin(2.0 * Double.pi * freq * t) * 0.85)
                }
                try engine.start()
                player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
                player.play()
                self.audioEngine = engine
                self.playerNode = player
            }
        } catch {
            print("Error iniciando alarma Find My iPhone: \(error)")
        }
    }

    private func startTorchStrobe() {
        var state = false
        torchTimer?.invalidate()
        torchTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { [weak self] _ in
            state.toggle()
            Task { @MainActor in
                self?.setTorch(on: state)
            }
        }
    }

    private func setTorch(on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        } catch {}
    }

    private func postLocalFindPhoneNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Tu reloj está buscando este iPhone"
        content.body = "Tocá para abrir Watch Bridge. Si la app está abierta, también suena la sirena."
        content.sound = UNNotificationSound(named: UNNotificationSoundName("find_iphone.caf"))
        content.interruptionLevel = .timeSensitive
        content.relevanceScore = 1.0

        let request = UNNotificationRequest(
            identifier: "find_my_iphone_alert",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
