import SwiftUI

struct ContentView: View {
    @StateObject private var ble = WatchBleCentralManager.shared
    @StateObject private var health = HealthKitSyncManager.shared
    @StateObject private var alarm = PhoneFindMyAlarmManager.shared

    @State private var selectedTab: Int = 0

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()

            TabView(selection: $selectedTab) {
                homeTab
                    .tabItem {
                        Label("Mi Reloj", systemImage: "applewatch")
                    }
                    .tag(0)

                appsFilterTab
                    .tabItem {
                        Label("Apps", systemImage: "bell.badge.fill")
                    }
                    .tag(1)

                inboxTab
                    .tabItem {
                        Label("Recientes", systemImage: "bubble.left.and.bubble.right.fill")
                    }
                    .tag(2)
            }
            .tint(.mint)

            if alarm.isRinging {
                findMyPhoneBanner
                    .padding()
                    .transition(.move(edge: .top))
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            health.requestAuthorizationIfNeeded()
            ble.scanOrReconnectWatch()
        }
    }

    // MARK: - Banner Alarma "Buscar mi iPhone"
    private var findMyPhoneBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "speaker.wave.3.fill")
                .font(.title2)
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text("Alarma de búsqueda activa")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("Activada desde tu Galaxy Watch 6")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
            Spacer()
            Button("Detener") {
                alarm.stopAlarm()
            }
            .buttonStyle(.borderedProminent)
            .tint(.black)
        }
        .padding(16)
        .background(Color.red.gradient)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .shadow(radius: 12)
    }

    // MARK: - Pestaña 1: Mi Reloj y Salud
    private var homeTab: some View {
        ScrollView {
            VStack(spacing: 18) {
                watchStatusHeaderCard
                healthMetricsSection
                mediaControlsCard
            }
            .padding(.horizontal, 18)
            .padding(.top, 16)
            .padding(.bottom, 32)
        }
        .background(Color.black)
    }

    private var watchStatusHeaderCard: some View {
        VStack(spacing: 16) {
            HStack {
                ZStack {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(white: 0.16))
                        .frame(width: 50, height: 50)
                    Image(systemName: "applewatch.watchface")
                        .font(.title2)
                        .foregroundStyle(.mint)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(ble.watchName)
                        .font(.headline)
                        .foregroundStyle(.white)
                    HStack(spacing: 6) {
                        Circle()
                            .fill(ble.isConnected ? Color.mint : Color.orange)
                            .frame(width: 8, height: 8)
                        Text(ble.connectionStatusText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    HStack(spacing: 4) {
                        Text("\(ble.telemetry.bat)%")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Image(systemName: ble.telemetry.chg ? "bolt.batteryblock.fill" : "battery.100")
                            .foregroundStyle(ble.telemetry.bat > 20 ? .mint : .red)
                    }
                    ProgressView(value: Double(max(5, min(100, ble.telemetry.bat))), total: 100)
                        .tint(ble.telemetry.bat > 20 ? .mint : .red)
                        .frame(width: 68)
                }
            }

            Divider().background(Color(white: 0.2))

            HStack(spacing: 10) {
                Button {
                    ble.requestSyncNow()
                } label: {
                    HStack {
                        Image(systemName: "arrow.triangle.2.circlepath")
                        Text(ble.isSyncingPulse ? "Sincronizando..." : "Actualizar BLE")
                    }
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.18))
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }

                Button {
                    ble.pingWatch()
                } label: {
                    HStack {
                        Image(systemName: "bell.and.waves.left.and.right.fill")
                        Text("Vibrar reloj")
                    }
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.18))
                    .foregroundStyle(.mint)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .padding(18)
        .background(Color(white: 0.09))
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(Color(white: 0.18), lineWidth: 1)
        )
    }

    private var healthMetricsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Salud (Sincronización Automática HealthKit)")
                    .font(.subheadline.bold())
                    .foregroundStyle(.secondary)
                Spacer()
                Image(systemName: health.isAuthorized ? "heart.text.square.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(health.isAuthorized ? .pink : .yellow)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                metricCard(
                    title: "Pasos hoy",
                    icon: "figure.walk",
                    value: "\(ble.telemetry.steps)",
                    unit: "pasos",
                    tint: .mint,
                    subtitle: "Sincronizado en Salud"
                )
                metricCard(
                    title: "Frecuencia",
                    icon: "heart.fill",
                    value: ble.telemetry.hr > 0 ? "\(ble.telemetry.hr)" : "--",
                    unit: "BPM",
                    tint: .pink,
                    subtitle: "Sensor óptico PPG"
                )
                metricCard(
                    title: "Peso corporal",
                    icon: "scalemass.fill",
                    value: ble.telemetry.wt.map { String(format: "%.1f", $0) } ?? "--",
                    unit: "kg",
                    tint: .cyan,
                    subtitle: "Samsung Health → iOS"
                )
                metricCard(
                    title: "Grasa corporal",
                    icon: "waveform.path.ecg",
                    value: ble.telemetry.fat.map { String(format: "%.1f", $0) } ?? "--",
                    unit: "%",
                    tint: .orange,
                    subtitle: "Bioimpedancia BIA"
                )
            }
        }
    }

    private func metricCard(
        title: String,
        icon: String,
        value: String,
        unit: String,
        tint: Color,
        subtitle: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Image(systemName: icon)
                    .foregroundStyle(tint)
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.title2.bold())
                    .foregroundStyle(tint)
                Text(unit)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(subtitle)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color(white: 0.09))
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color(white: 0.16), lineWidth: 1)
        )
    }

    private var mediaControlsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Control Rápido del Reloj")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                Spacer()
                let isMuted = ble.telemetry.dnd ?? false
                Button {
                    ble.setWatchMuted(!isMuted)
                } label: {
                    Text(isMuted ? "🔕 Reloj Silenciado" : "🔔 Sonido/Vibración Activo")
                        .font(.caption2.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(isMuted ? Color.red.opacity(0.25) : Color.mint.opacity(0.2))
                        .foregroundStyle(isMuted ? .red : .mint)
                        .clipShape(Capsule())
                }
            }

            HStack(spacing: 12) {
                Button {
                    ble.sendMediaCommand("PREVIOUS_TRACK")
                } label: {
                    Image(systemName: "backward.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color(white: 0.18))
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }

                Button {
                    ble.sendMediaCommand("TOGGLE_PLAY_PAUSE")
                } label: {
                    Image(systemName: "playpause.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.blue)
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }

                Button {
                    ble.sendMediaCommand("NEXT_TRACK")
                } label: {
                    Image(systemName: "forward.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color(white: 0.18))
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .padding(18)
        .background(Color(white: 0.09))
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    // MARK: - Pestaña 2: Filtro de Aplicaciones (Dinámico + Catálogo)
    private var appsFilterTab: some View {
        List {
            Section {
                ForEach(ble.discoveredApps) { app in
                    let isAllowed = !ble.blockedBundles.contains(app.bundleId)
                    HStack(spacing: 12) {
                        Text(app.icon)
                            .font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.name)
                                .font(.body.weight(.medium))
                            Text(app.bundleId)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { isAllowed },
                            set: { _ in ble.toggleBundleAllowed(app.bundleId) }
                        ))
                        .labelsHidden()
                        .tint(.mint)
                    }
                    .padding(.vertical, 4)
                }
            } header: {
                Text("Elige qué apps de tu iPhone vibran en el Galaxy Watch")
            } footer: {
                Text("Cualquier app nueva que envíe notificaciones a tu reloj aparecerá automáticamente en esta lista.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.black)
    }

    // MARK: - Pestaña 3: Historial Reciente de Notificaciones
    private var inboxTab: some View {
        Group {
            if ble.recentNotifications.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "tray")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("Aún no han entrado notificaciones recientes.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
            } else {
                List(ble.recentNotifications) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(item.appName)
                                .font(.caption.bold())
                                .foregroundStyle(.cyan)
                            Spacer()
                            Text(item.formattedTime)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(item.title)
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        if !item.msg.isEmpty {
                            Text(item.msg)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(3)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .scrollContentBackground(.hidden)
                .background(Color.black)
            }
        }
    }
}
