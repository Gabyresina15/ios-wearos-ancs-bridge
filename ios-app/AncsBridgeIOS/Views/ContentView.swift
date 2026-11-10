import SwiftUI

private let bgObsidian = Color(red: 0.04, green: 0.04, blue: 0.055)
private let cardSurface = Color(red: 0.08, green: 0.085, blue: 0.11)
private let cardElevated = Color(red: 0.11, green: 0.12, blue: 0.155)
private let cardStroke = Color.white.opacity(0.08)
private let accentEmerald = Color(red: 0.06, green: 0.72, blue: 0.50)
private let accentCyan = Color(red: 0.02, green: 0.71, blue: 0.83)
private let accentCoral = Color(red: 0.96, green: 0.25, blue: 0.37)
private let accentAmber = Color(red: 0.96, green: 0.62, blue: 0.04)

struct ContentView: View {
    @StateObject private var ble = WatchBleCentralManager.shared
    @StateObject private var health = HealthKitSyncManager.shared
    @StateObject private var alarm = PhoneFindMyAlarmManager.shared

    @State private var selectedTab: Int = 0
    @State private var showFemmtoSheet: Bool = false
    @State private var appSearchText: String = ""

    // Campos del modal de Balanza Femmto
    @State private var inputWeightKg: String = ""
    @State private var inputFatPct: String = ""
    @State private var inputHeightCm: String = ""

    var body: some View {
        ZStack(alignment: .top) {
            bgObsidian.ignoresSafeArea()

            TabView(selection: $selectedTab) {
                myWatchTab
                    .tabItem {
                        Label("Mi Reloj", systemImage: "applewatch.watchface")
                    }
                    .tag(0)

                healthAndFemmtoTab
                    .tabItem {
                        Label("Salud & Femmto", systemImage: "heart.text.square.fill")
                    }
                    .tag(1)

                notificationsFilterTab
                    .tabItem {
                        Label("Alertas", systemImage: "bell.badge.fill")
                    }
                    .tag(2)

                recentInboxTab
                    .tabItem {
                        Label("Actividad", systemImage: "clock.arrow.circlepath")
                    }
                    .tag(3)
            }
            .tint(accentEmerald)

            if alarm.isRinging {
                findMyPhoneBanner
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showFemmtoSheet) {
            femmtoEditorSheet
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onAppear {
            health.requestAuthorizationIfNeeded()
            ble.requestCalendarAccess()
            ble.reconnect()
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
                Text("Disparada desde \(ble.watchName)")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
            Spacer()
            Button("Detener") {
                alarm.stopAlarm()
                ble.notifyPhoneAlarmStopped()
            }
            .buttonStyle(.borderedProminent)
            .tint(.black)
        }
        .padding(16)
        .background(accentCoral.gradient)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .shadow(color: accentCoral.opacity(0.5), radius: 14, y: 6)
    }

    // =========================================================================
    // PESTAÑA 1: MI RELOJ (ESTILO GALAXY WEARABLE / APPLE WATCH STUDIO)
    // =========================================================================

    private var myWatchTab: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    watchHeroCard
                    notificationsMasterPauseCard
                    quickActionsGrid
                    mediaRemoteCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 32)
            }
            .background(bgObsidian)
            .navigationTitle("Watch Bridge")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var watchHeroCard: some View {
        VStack(spacing: 16) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(
                            LinearGradient(
                                colors: ble.isConnected ? [accentEmerald, accentCyan] : [accentAmber, Color.gray.opacity(0.4)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 2.5
                        )
                        .frame(width: 66, height: 66)

                    Image("AppLogo")
                        .resizable()
                        .scaledToFill()
                        .frame(width: 58, height: 58)
                        .clipShape(Circle())
                }

                VStack(alignment: .leading, spacing: 5) {
                    Text(ble.watchName)
                        .font(.title3.bold())
                        .foregroundStyle(.white)
                        .lineLimit(1)

                    HStack(spacing: 6) {
                        Circle()
                            .fill(ble.isConnected ? accentEmerald : accentAmber)
                            .frame(width: 8, height: 8)
                        Text(ble.linkState.title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(ble.isConnected ? accentEmerald : .secondary)
                    }

                    HStack(spacing: 8) {
                        Label(
                            ble.telemetry.hfp ? "Llamadas HFP listas" : "BLE Directo",
                            systemImage: ble.telemetry.hfp ? "phone.wave.2.fill" : "bolt.horizontal.circle.fill"
                        )
                        .font(.caption2)
                        .foregroundStyle(accentCyan)
                    }
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 6) {
                    HStack(spacing: 4) {
                        Text(ble.telemetry.bat > 0 ? "\(ble.telemetry.bat)%" : "--%")
                            .font(.title3.bold())
                            .foregroundStyle(.white)
                        Image(systemName: ble.telemetry.chg ? "bolt.batteryblock.fill" : "battery.100")
                            .foregroundStyle(ble.telemetry.bat > 20 ? accentEmerald : accentCoral)
                    }
                    ProgressView(value: Double(max(5, min(100, ble.telemetry.bat))), total: 100)
                        .tint(ble.telemetry.bat > 20 ? accentEmerald : accentCoral)
                        .frame(width: 72)
                    Text(ble.telemetry.chg ? "Cargando" : "Batería reloj")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if !ble.isConnected {
                HStack(spacing: 10) {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(accentCyan)
                    Text("Abrí la app Watch Bridge en \(ble.watchName) o tocá Conectar para enlazar al instante.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(10)
                .background(cardElevated)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            HStack(spacing: 10) {
                Button {
                    ble.reconnect()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                        Text(ble.isSyncing ? "Sincronizando…" : (ble.isConnected ? "Sincronizar ahora" : "Conectar reloj"))
                    }
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(
                        LinearGradient(
                            colors: [accentEmerald.opacity(0.25), accentCyan.opacity(0.25)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(accentEmerald.opacity(0.45), lineWidth: 1)
                    )
                }

                Button {
                    ble.toggleFindWatch()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: ble.isRingingWatch ? "speaker.slash.fill" : "bell.and.waves.left.and.right.fill")
                        Text(ble.isRingingWatch ? "Parar alarma" : "Buscar reloj")
                    }
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(ble.isRingingWatch ? accentCoral.opacity(0.25) : cardElevated)
                    .foregroundStyle(ble.isRingingWatch ? accentCoral : accentCyan)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .padding(18)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(cardStroke, lineWidth: 1)
        )
    }

    // MARK: - Interruptor Maestro para Silenciar / Pausar Notificaciones en el Reloj

    private var notificationsMasterPauseCard: some View {
        let isMuted = ble.telemetry.dnd
        return HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(isMuted ? accentCoral.opacity(0.18) : accentEmerald.opacity(0.18))
                    .frame(width: 46, height: 46)
                Image(systemName: isMuted ? "bell.slash.fill" : "bell.badge.fill")
                    .font(.title3)
                    .foregroundStyle(isMuted ? accentCoral : accentEmerald)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(isMuted ? "Notificaciones Pausadas en el Reloj" : "Notificaciones Activas en el Reloj")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Text(isMuted
                     ? "No saldrán alertas ni el punto naranja en \(ble.watchName) hasta que lo actives."
                     : "Los mensajes aparecen y vibran en \(ble.watchName).")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()

            Toggle("", isOn: Binding(
                get: { !isMuted },
                set: { active in ble.setWatchMuted(!active) }
            ))
            .labelsHidden()
            .tint(accentEmerald)
        }
        .padding(16)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(isMuted ? accentCoral.opacity(0.4) : accentEmerald.opacity(0.3), lineWidth: 1)
        )
    }

    // MARK: - Grilla de Acciones Rápidas

    private var quickActionsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            quickActionButton(
                title: "Medir Pulso",
                subtitle: "Sensor óptico del reloj",
                icon: "waveform.path.ecg",
                tint: accentCoral
            ) {
                ble.measureWatchHeartRate()
            }

            quickActionButton(
                title: "Balanza Femmto",
                subtitle: health.bodyComposition.weightKg.map { String(format: "%.1f kg", $0) } ?? "Cargar pesada",
                icon: "scalemass.fill",
                tint: accentCyan
            ) {
                prepareFemmtoInputs()
                showFemmtoSheet = true
            }

            quickActionButton(
                title: "Limpiar Reloj",
                subtitle: "Quitar punto naranja",
                icon: "sparkles",
                tint: accentAmber
            ) {
                ble.clearWatchNotifications()
            }

            quickActionButton(
                title: "Probar Sirena",
                subtitle: alarm.isRinging ? "Detener alarma" : "Alarma local iOS",
                icon: "megaphone.fill",
                tint: accentEmerald
            ) {
                alarm.triggerFindPhoneAlarm()
            }
        }
    }

    private func quickActionButton(
        title: String,
        subtitle: String,
        icon: String,
        tint: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(tint.opacity(0.16))
                        .frame(width: 40, height: 40)
                    Image(systemName: icon)
                        .font(.body.bold())
                        .foregroundStyle(tint)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.caption.bold())
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(13)
            .background(cardSurface)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(cardStroke, lineWidth: 1)
            )
        }
    }

    // MARK: - Control Multimedia Sincronizado (AMS)

    private var mediaRemoteCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Control Multimedia (AMS)", systemImage: "music.quarternote.3")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                Spacer()
                if let vol = ble.telemetry.mVol {
                    Text("🔊 \(vol)%")
                        .font(.caption2.bold())
                        .foregroundStyle(accentCyan)
                } else {
                    Text(ble.watchName)
                        .font(.caption2)
                        .foregroundStyle(accentCyan)
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(ble.telemetry.mTitle)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(ble.telemetry.mArtist)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            HStack(spacing: 10) {
                mediaButton(icon: "speaker.minus.fill", action: "VOLUME_DOWN")
                mediaButton(icon: "backward.fill", action: "PREVIOUS_TRACK")
                Button {
                    ble.sendMediaCommand("TOGGLE_PLAY_PAUSE")
                } label: {
                    Image(systemName: ble.telemetry.mPlay ? "pause.fill" : "play.fill")
                        .font(.body.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            LinearGradient(colors: [accentEmerald, accentCyan], startPoint: .leading, endPoint: .trailing)
                        )
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                mediaButton(icon: "forward.fill", action: "NEXT_TRACK")
                mediaButton(icon: "speaker.plus.fill", action: "VOLUME_UP")
            }
        }
        .padding(16)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(cardStroke, lineWidth: 1)
        )
    }

    private func mediaButton(icon: String, action: String) -> some View {
        Button {
            ble.sendMediaCommand(action)
        } label: {
            Image(systemName: icon)
                .font(.caption.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(cardElevated)
                .foregroundStyle(.white)
                .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    // =========================================================================
    // PESTAÑA 2: SALUD DEL RELOJ + BALANZA INTELIGENTE FEMMTO
    // =========================================================================

    private var healthAndFemmtoTab: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    watchActivityCard
                    femmtoScaleCard
                    femmtoShortcutGuideCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 32)
            }
            .background(bgObsidian)
            .navigationTitle("Salud y Femmto")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var watchActivityCard: some View {
        let steps = ble.telemetry.steps
        let goal = max(1000, health.dailyStepGoal)
        let progress = min(1.0, Double(steps) / Double(goal))
        let distanceKm = Double(steps) * 0.00076
        let activeKcal = Int(Double(steps) * 0.042)

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Actividad de \(ble.watchName)", systemImage: "figure.run.circle.fill")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer()
                Button {
                    ble.measureWatchHeartRate()
                } label: {
                    Label("Medir BPM", systemImage: "heart.fill")
                        .font(.caption2.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(accentCoral.opacity(0.2))
                        .foregroundStyle(accentCoral)
                        .clipShape(Capsule())
                }
            }

            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(steps)")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundStyle(accentEmerald)
                    Text("de \(goal) pasos hoy")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ProgressView(value: progress)
                        .tint(accentEmerald)
                        .padding(.top, 4)
                }

                Divider().frame(height: 58).background(cardStroke)

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(systemName: "heart.fill")
                            .foregroundStyle(accentCoral)
                        Text(ble.telemetry.hr > 0 ? "\(ble.telemetry.hr) BPM" : "-- BPM")
                            .font(.headline.bold())
                            .foregroundStyle(.white)
                    }
                    HStack(spacing: 6) {
                        Image(systemName: "location.fill")
                            .foregroundStyle(accentCyan)
                        Text(String(format: "%.2f km", distanceKm))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    HStack(spacing: 6) {
                        Image(systemName: "flame.fill")
                            .foregroundStyle(accentAmber)
                        Text("\(activeKcal) kcal activas")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(18)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(cardStroke, lineWidth: 1)
        )
    }

    private var femmtoScaleCard: some View {
        let comp = health.bodyComposition
        let weightText = comp.weightKg.map { String(format: "%.1f", $0) } ?? "--"
        let fatText = comp.bodyFatPercent.map { String(format: "%.1f", $0) } ?? "--"
        let bmiText = comp.bmi.map { String(format: "%.1f", $0) } ?? "--"
        let leanText = comp.leanMassKg.map { String(format: "%.1f", $0) } ?? "--"

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Image(systemName: "scalemass.fill")
                            .foregroundStyle(accentCyan)
                        Text("Balanza Inteligente Femmto")
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                    }
                    if let measuredAt = comp.measuredAt {
                        Text("Última pesada: \(measuredAt.formatted(date: .abbreviated, time: .shortened)) • Sincronizado con \(ble.watchName)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Sincronizá desde Salud o cargá tu pesada de Femmto para verla en \(ble.watchName)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                femmtoMetricTile(title: "Peso Corporal", value: weightText, unit: "kg", tint: accentCyan)
                femmtoMetricTile(title: "Grasa Corporal", value: fatText, unit: "%", tint: accentAmber)
                femmtoMetricTile(title: "IMC (BMI)", value: bmiText, unit: "kg/m²", tint: accentEmerald)
                femmtoMetricTile(title: "Masa Magra", value: leanText, unit: "kg", tint: .purple)
            }

            HStack(spacing: 10) {
                Button {
                    prepareFemmtoInputs()
                    showFemmtoSheet = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "plus.circle.fill")
                        Text("Actualizar pesada Femmto")
                    }
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(accentCyan.opacity(0.22))
                    .foregroundStyle(accentCyan)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(accentCyan.opacity(0.45), lineWidth: 1)
                    )
                }

                Button {
                    Task {
                        await health.refreshBodyComposition()
                        ble.sendBodyComposition(health.bodyComposition)
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.clockwise.heart.fill")
                        Text("Leer Salud")
                    }
                    .font(.caption.bold())
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(cardElevated)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .padding(18)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(accentCyan.opacity(0.3), lineWidth: 1)
        )
    }

    private func femmtoMetricTile(title: String, value: String, unit: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.title2.bold())
                    .foregroundStyle(tint)
                Text(unit)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(cardElevated)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var femmtoShortcutGuideCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "bolt.badge.automatic.fill")
                    .foregroundStyle(accentEmerald)
                Text("Tip: Automatización con la app Femmto")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
            }
            Text("Si en la app Atajos creás una Automatización personal 'Al cerrar la app Femmto' → 'Buscar muestras de Salud (Peso y % Grasa)' → 'Abrir URL':")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text("watchbridge://femmto?wt=[Peso]&fat=[Grasa]")
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(accentCyan)
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(cardElevated)
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .padding(16)
        .background(cardSurface)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(cardStroke, lineWidth: 1)
        )
    }

    // MARK: - Modal de Carga Rápida Balanza Femmto

    private var femmtoEditorSheet: some View {
        NavigationStack {
            Form {
                Section("Medición de tu balanza Femmto") {
                    HStack {
                        Text("Peso corporal (kg)")
                        Spacer()
                        TextField("Ej: 76.4", text: $inputWeightKg)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                    }
                    HStack {
                        Text("Grasa corporal (%)")
                        Spacer()
                        TextField("Ej: 18.5", text: $inputFatPct)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                    }
                    HStack {
                        Text("Tu altura (cm, para IMC)")
                        Spacer()
                        TextField("Ej: 175", text: $inputHeightCm)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section {
                    Button {
                        saveFemmtoSheetValues()
                    } label: {
                        HStack {
                            Spacer()
                            Label("Guardar y enviar a \(ble.watchName)", systemImage: "paperplane.fill")
                                .font(.subheadline.bold())
                            Spacer()
                        }
                    }
                    .tint(accentEmerald)
                } footer: {
                    Text("Calcula automáticamente tu IMC y tu Masa Magra y actualiza la tarjeta de Femmto en \(ble.watchName).")
                }
            }
            .navigationTitle("Balanza Femmto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { showFemmtoSheet = false }
                }
            }
        }
    }

    private func prepareFemmtoInputs() {
        if let w = health.bodyComposition.weightKg {
            inputWeightKg = String(format: "%.1f", w)
        }
        if let f = health.bodyComposition.bodyFatPercent {
            inputFatPct = String(format: "%.1f", f)
        }
        inputHeightCm = String(format: "%.0f", health.userHeightCm)
    }

    private func saveFemmtoSheetValues() {
        let cleanWeight = inputWeightKg.replacingOccurrences(of: ",", with: ".")
        let cleanFat = inputFatPct.replacingOccurrences(of: ",", with: ".")
        let cleanHeight = inputHeightCm.replacingOccurrences(of: ",", with: ".")

        guard let w = Double(cleanWeight), w > 15 else { return }
        let f = Double(cleanFat)
        let h = Double(cleanHeight)

        health.saveFemmtoMeasurement(weightKg: w, bodyFatPercent: f, heightCm: h, source: "Femmto")
        ble.sendBodyComposition(health.bodyComposition)
        showFemmtoSheet = false
    }

    // =========================================================================
    // PESTAÑA 3: FILTRO DE NOTIFICACIONES POR APP
    // =========================================================================

    private var filteredApps: [IosAppFilterCatalogItem] {
        let query = appSearchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return ble.discoveredApps }
        return ble.discoveredApps.filter {
            $0.name.lowercased().contains(query) || $0.bundleId.lowercased().contains(query)
        }
    }

    private var notificationsFilterTab: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        Image(systemName: ble.telemetry.dnd ? "bell.slash.fill" : "bell.badge.fill")
                            .foregroundStyle(ble.telemetry.dnd ? accentCoral : accentEmerald)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Mostrar notificaciones en el reloj")
                                .font(.body.weight(.semibold))
                            Text(ble.telemetry.dnd
                                 ? "Pausadas: no saldrá ningún mensaje ni punto naranja"
                                 : "Activas: el reloj muestra las apps habilitadas abajo")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { !ble.telemetry.dnd },
                            set: { enabled in ble.setWatchMuted(!enabled) }
                        ))
                        .labelsHidden()
                        .tint(accentEmerald)
                    }
                } header: {
                    Text("Interruptor General")
                }

                Section {
                    ForEach(filteredApps) { app in
                        let allowed = ble.isAllowed(app.bundleId)
                        HStack(spacing: 12) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(allowed ? accentEmerald.opacity(0.18) : Color.gray.opacity(0.15))
                                    .frame(width: 36, height: 36)
                                Image(systemName: app.symbol)
                                    .foregroundStyle(allowed ? accentEmerald : .secondary)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.name)
                                    .font(.body.weight(.medium))
                                Text(app.bundleId)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { allowed },
                                set: { newVal in ble.setAllowed(newVal, bundleId: app.bundleId) }
                            ))
                            .labelsHidden()
                            .tint(accentEmerald)
                        }
                        .padding(.vertical, 2)
                    }
                } header: {
                    Text("Aplicaciones permitidas en \(ble.watchName)")
                } footer: {
                    Text("El reloj consulta automáticamente a iOS el nombre oficial de cada aplicación mediante el comando nativo ANCS GetAppAttributes (0x01) y la agrega a esta lista.")
                }
            }
            .searchable(text: $appSearchText, prompt: "Buscar aplicación...")
            .scrollContentBackground(.hidden)
            .background(bgObsidian)
            .navigationTitle("Notificaciones")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // =========================================================================
    // PESTAÑA 4: HISTORIAL RECIENTE DE ACTIVIDAD
    // =========================================================================

    private var recentInboxTab: some View {
        NavigationStack {
            Group {
                if ble.recentNotifications.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "tray.fill")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary)
                        Text("Sin notificaciones recientes")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text("Aquí verás el registro en vivo de los mensajes recibidos en \(ble.watchName).")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(bgObsidian)
                } else {
                    List {
                        ForEach(ble.recentNotifications) { item in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(item.appName)
                                        .font(.caption.bold())
                                        .foregroundStyle(accentCyan)
                                    Spacer()
                                    Text(item.date.formatted(date: .omitted, time: .shortened))
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
                    }
                    .scrollContentBackground(.hidden)
                    .background(bgObsidian)
                }
            }
            .navigationTitle("Actividad")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !ble.recentNotifications.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Limpiar") {
                            ble.clearRecentNotifications()
                        }
                        .foregroundStyle(accentCoral)
                    }
                }
            }
        }
    }
}
