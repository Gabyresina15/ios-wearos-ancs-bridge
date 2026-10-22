import React, { useEffect, useRef, useState } from "react";

interface WatchStatus {
  state: string;
  stateLabel: string;
  hfpConnected?: boolean;
  panConnected?: boolean;
  watchBatteryPercent: number;
  isCharging: boolean;
  deviceModel: string;
  pairedIphone: string;
  lastSeen: number;
}

interface TelemetryData {
  watchBatteryPercent: number;
  isCharging: boolean;
  stepsToday: number;
  latestHeartRateBpm: number;
  weightKg: number | null;
  bodyFatPercentage: number | null;
  timestamp: number;
}

interface AncsNotification {
  uid: number;
  appIdentifier: string;
  appDisplayName: string;
  title: string;
  subtitle: string;
  message: string;
  timestamp: number;
}

const POPULAR_IOS_APPS = [
  { name: "WhatsApp", bundleId: "net.whatsapp.WhatsApp", icon: "💬" },
  { name: "Instagram", bundleId: "com.burbn.instagram", icon: "📸" },
  { name: "Llamadas", bundleId: "com.apple.mobilephone", icon: "📞" },
  { name: "iMessage / SMS", bundleId: "com.apple.MobileSMS", icon: "✉️" },
  { name: "Telegram", bundleId: "ph.telegra.Telegraph", icon: "✈️" },
  { name: "Gmail", bundleId: "com.google.Gmail", icon: "📧" },
  { name: "Mercado Pago", bundleId: "com.mercadopago.MercadoPago", icon: "💳" },
  { name: "Calendario", bundleId: "com.apple.mobilecal", icon: "📅" },
];

const WS_URL = import.meta.env.VITE_WS_URL || "ws://localhost:8080/ws?role=web";

export default function App() {
  const wsRef = useRef<WebSocket | null>(null);
  const [cloudConnected, setCloudConnected] = useState(false);
  const [syncingPulse, setSyncingPulse] = useState(false);
  const [activeTab, setActiveTab] = useState<"home" | "apps" | "inbox">("home");

  const [watchStatus, setWatchStatus] = useState<WatchStatus>({
    state: "DISCONNECTED",
    stateLabel: "Desconectado",
    watchBatteryPercent: 0,
    isCharging: false,
    deviceModel: "Galaxy Watch 6 Classic",
    pairedIphone: "iPhone 16 Pro Max",
    lastSeen: 0,
  });

  const [telemetry, setTelemetry] = useState<TelemetryData>({
    watchBatteryPercent: 0,
    isCharging: false,
    stepsToday: 0,
    latestHeartRateBpm: 0,
    weightKg: null,
    bodyFatPercentage: null,
    timestamp: 0,
  });

  const [notifications, setNotifications] = useState<AncsNotification[]>([]);
  const [blockedBundles, setBlockedBundles] = useState<string[]>([]);

  useEffect(() => {
    let reconnectTimer: number;
    const connect = () => {
      const ws = new WebSocket(WS_URL);
      wsRef.current = ws;

      ws.onopen = () => setCloudConnected(true);
      ws.onclose = () => {
        setCloudConnected(false);
        reconnectTimer = window.setTimeout(connect, 3000);
      };

      ws.onmessage = (event) => {
        const payload = JSON.parse(event.data);
        if (payload.type === "INITIAL_SNAPSHOT") {
          setWatchStatus(payload.data.watchStatus);
          setTelemetry(payload.data.telemetry);
          setNotifications(payload.data.notificationHistory || []);
          setBlockedBundles(payload.data.blockedBundles || []);
        } else if (payload.type === "WATCH_STATUS_UPDATED") {
          setWatchStatus(payload.data);
        } else if (payload.type === "TELEMETRY_UPDATED") {
          setTelemetry(payload.data);
          setSyncingPulse(false);
        } else if (payload.type === "NEW_ANCS_NOTIFICATION") {
          setNotifications((prev) =>
            [payload.data, ...prev.filter((n) => n.uid !== payload.data.uid)].slice(0, 50)
          );
        } else if (payload.type === "CONFIG_UPDATED") {
          setBlockedBundles(payload.blockedBundles || []);
        }
      };
    };

    connect();
    return () => {
      clearTimeout(reconnectTimer);
      wsRef.current?.close();
    };
  }, []);

  const syncWatchNow = () => {
    setSyncingPulse(true);
    wsRef.current?.send(JSON.stringify({ type: "REQUEST_TELEMETRY" }));
    setTimeout(() => setSyncingPulse(false), 2500);
  };

  const findMyWatch = () => {
    wsRef.current?.send(
      JSON.stringify({
        type: "REMOTE_ALERT",
        title: "Buscando tu reloj 🔔",
        message: "Ping enviado desde tu iPhone 16 Pro Max",
      })
    );
  };

  const toggleAppAllowed = (bundleId: string) => {
    const updated = blockedBundles.includes(bundleId)
      ? blockedBundles.filter((b) => b !== bundleId)
      : [...blockedBundles, bundleId];

    wsRef.current?.send(
      JSON.stringify({
        type: "SYNC_CONFIG",
        blockedBundles: updated,
      })
    );
  };

  const sendMediaCommand = (command: string) => {
    wsRef.current?.send(JSON.stringify({ type: "AMS_COMMAND", command }));
  };

  const isWatchOnline =
    watchStatus.state === "READY" ||
    (telemetry.timestamp > 0 && Date.now() - telemetry.timestamp < 30 * 60 * 1000);

  const batteryLevel = telemetry.watchBatteryPercent || watchStatus.watchBatteryPercent || 0;
  const isCharging = telemetry.isCharging || watchStatus.isCharging;

  return (
    <div className="min-h-screen bg-black text-white font-sans pb-24 select-none">
      <div className="max-w-md mx-auto px-5 pt-8 space-y-6">
        <div className="bg-zinc-900/90 border border-zinc-800/80 rounded-3xl p-6 shadow-2xl">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3.5">
              <div className="w-12 h-12 rounded-2xl bg-zinc-800 flex items-center justify-center text-2xl shadow-inner">
                ⌚
              </div>
              <div>
                <h1 className="text-lg font-bold tracking-tight">Galaxy Watch 6</h1>
                <div className="flex items-center gap-2 mt-0.5">
                  <span
                    className={`w-2 h-2 rounded-full ${
                      isWatchOnline ? "bg-emerald-400" : "bg-amber-400 animate-pulse"
                    }`}
                  />
                  <span className="text-xs text-zinc-400">
                    {isWatchOnline
                      ? "Conectado a tu iPhone"
                      : cloudConnected
                      ? "En espera / Ahorro"
                      : "Reconectando..."}
                  </span>
                </div>
              </div>
            </div>

            <div className="text-right">
              <div className="flex items-center justify-end gap-1.5">
                <span className="text-lg font-bold">{batteryLevel}%</span>
                <span className="text-xs">{isCharging ? "⚡" : "🔋"}</span>
              </div>
              <div className="w-16 h-1.5 bg-zinc-800 rounded-full overflow-hidden mt-1 ml-auto">
                <div
                  className={`h-full rounded-full transition-all duration-500 ${
                    batteryLevel > 20 ? "bg-emerald-400" : "bg-red-500"
                  }`}
                  style={{ width: `${Math.min(100, Math.max(5, batteryLevel))}%` }}
                />
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3 mt-5 pt-4 border-t border-zinc-800/80">
            <button
              onClick={syncWatchNow}
              className="flex items-center justify-center gap-2 bg-zinc-800 hover:bg-zinc-700 active:scale-95 text-xs font-semibold py-2.5 px-4 rounded-2xl transition"
            >
              <span className={syncingPulse ? "animate-spin" : ""}>🔄</span>
              {syncingPulse ? "Sincronizando..." : "Actualizar datos"}
            </button>
            <button
              onClick={findMyWatch}
              className="flex items-center justify-center gap-2 bg-zinc-800 hover:bg-zinc-700 active:scale-95 text-xs font-semibold py-2.5 px-4 rounded-2xl transition"
            >
              <span>🔔</span> Hacer vibrar reloj
            </button>
          </div>
        </div>

        {activeTab === "home" && (
          <>
            <div className="space-y-3">
              <div className="flex items-center justify-between px-1">
                <h2 className="text-sm font-semibold text-zinc-400">Actividad y Salud</h2>
                <a
                  href="shortcuts://run-shortcut?name=Sync%20Watch%20Health"
                  className="text-xs font-semibold text-emerald-400 hover:text-emerald-300"
                >
                  Enviar a Apple Health →
                </a>
              </div>

              <div className="grid grid-cols-2 gap-3.5">
                <div className="bg-zinc-900/90 border border-zinc-800/80 p-4 rounded-3xl">
                  <div className="flex items-center justify-between text-xs text-zinc-400">
                    <span>Pasos hoy</span>
                    <span>👣</span>
                  </div>
                  <p className="text-2xl font-bold mt-2 text-emerald-400">
                    {telemetry.stepsToday.toLocaleString()}
                  </p>
                  <p className="text-[11px] text-zinc-500 mt-1">Meta diaria: 8,000</p>
                </div>

                <div className="bg-zinc-900/90 border border-zinc-800/80 p-4 rounded-3xl">
                  <div className="flex items-center justify-between text-xs text-zinc-400">
                    <span>Frecuencia</span>
                    <span>❤️</span>
                  </div>
                  <p className="text-2xl font-bold mt-2 text-rose-400">
                    {telemetry.latestHeartRateBpm || "--"}{" "}
                    <span className="text-xs font-normal text-zinc-400">BPM</span>
                  </p>
                  <p className="text-[11px] text-zinc-500 mt-1">Última lectura</p>
                </div>

                <div className="bg-zinc-900/90 border border-zinc-800/80 p-4 rounded-3xl">
                  <div className="flex items-center justify-between text-xs text-zinc-400">
                    <span>Peso corporal</span>
                    <span>⚖️</span>
                  </div>
                  <p className="text-2xl font-bold mt-2 text-sky-400">
                    {telemetry.weightKg ? `${telemetry.weightKg.toFixed(1)}` : "--"}{" "}
                    <span className="text-xs font-normal text-zinc-400">kg</span>
                  </p>
                  <p className="text-[11px] text-zinc-500 mt-1">Sensor reloj</p>
                </div>

                <div className="bg-zinc-900/90 border border-zinc-800/80 p-4 rounded-3xl">
                  <div className="flex items-center justify-between text-xs text-zinc-400">
                    <span>Grasa corporal</span>
                    <span>⚡</span>
                  </div>
                  <p className="text-2xl font-bold mt-2 text-amber-400">
                    {telemetry.bodyFatPercentage
                      ? `${telemetry.bodyFatPercentage.toFixed(1)}`
                      : "--"}{" "}
                    <span className="text-xs font-normal text-zinc-400">%</span>
                  </p>
                  <p className="text-[11px] text-zinc-500 mt-1">Composición BIA</p>
                </div>
              </div>
            </div>

            <div className="bg-zinc-900/90 border border-zinc-800/80 p-5 rounded-3xl space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-zinc-400">Control Multimedia</span>
                <span className="text-[11px] text-zinc-500">iPhone ⇄ Watch</span>
              </div>
              <div className="grid grid-cols-3 gap-3">
                <button
                  onClick={() => sendMediaCommand("PREVIOUS_TRACK")}
                  className="bg-zinc-800 hover:bg-zinc-700 active:scale-95 py-3 rounded-2xl text-sm font-medium transition"
                >
                  ⏮
                </button>
                <button
                  onClick={() => sendMediaCommand("TOGGLE_PLAY_PAUSE")}
                  className="bg-blue-600 hover:bg-blue-500 active:scale-95 py-3 rounded-2xl text-sm font-bold transition"
                >
                  ⏯
                </button>
                <button
                  onClick={() => sendMediaCommand("NEXT_TRACK")}
                  className="bg-zinc-800 hover:bg-zinc-700 active:scale-95 py-3 rounded-2xl text-sm font-medium transition"
                >
                  ⏭
                </button>
              </div>
            </div>
          </>
        )}

        {activeTab === "apps" && (
          <div className="bg-zinc-900/90 border border-zinc-800/80 rounded-3xl p-5 space-y-4">
            <div>
              <h2 className="text-base font-bold">Notificaciones en el Reloj</h2>
              <p className="text-xs text-zinc-400 mt-0.5">
                Elige qué aplicaciones de tu iPhone pueden vibrar en tu Galaxy Watch.
              </p>
            </div>

            <div className="divide-y divide-zinc-800/70">
              {POPULAR_IOS_APPS.map((app) => {
                const isEnabled = !blockedBundles.includes(app.bundleId);
                return (
                  <div
                    key={app.bundleId}
                    className="flex items-center justify-between py-3.5"
                  >
                    <div className="flex items-center gap-3">
                      <span className="text-xl">{app.icon}</span>
                      <span className="text-sm font-medium">{app.name}</span>
                    </div>
                    <button
                      onClick={() => toggleAppAllowed(app.bundleId)}
                      className={`w-12 h-7 flex items-center rounded-full p-1 transition-colors ${
                        isEnabled ? "bg-emerald-500" : "bg-zinc-700"
                      }`}
                    >
                      <div
                        className={`bg-white w-5 h-5 rounded-full shadow-md transform transition-transform ${
                          isEnabled ? "translate-x-5" : "translate-x-0"
                        }`}
                      />
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {activeTab === "inbox" && (
          <div className="bg-zinc-900/90 border border-zinc-800/80 rounded-3xl p-5 space-y-3">
            <h2 className="text-base font-bold">Recientes en tu Reloj</h2>
            {notifications.length === 0 ? (
              <p className="text-xs text-zinc-500 py-8 text-center">
                Aún no han entrado notificaciones recientes.
              </p>
            ) : (
              <div className="space-y-2.5">
                {notifications.map((n) => (
                  <div
                    key={n.uid}
                    className="bg-zinc-950/80 border border-zinc-800/70 p-3.5 rounded-2xl flex items-start justify-between gap-3"
                  >
                    <div className="min-w-0">
                      <span className="text-[11px] font-bold text-sky-400">
                        {n.appDisplayName}
                      </span>
                      <p className="text-sm font-semibold truncate mt-0.5">{n.title}</p>
                      <p className="text-xs text-zinc-400 line-clamp-2">
                        {n.message || n.subtitle}
                      </p>
                    </div>
                    <span className="text-[10px] text-zinc-500 shrink-0">
                      {new Date(n.timestamp).toLocaleTimeString([], {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      <nav className="fixed bottom-0 inset-x-0 bg-zinc-950/90 backdrop-blur-md border-t border-zinc-800/80">
        <div className="max-w-md mx-auto grid grid-cols-3 py-2.5">
          <button
            onClick={() => setActiveTab("home")}
            className={`flex flex-col items-center gap-1 text-xs font-medium ${
              activeTab === "home" ? "text-emerald-400" : "text-zinc-500"
            }`}
          >
            <span className="text-lg">⌚</span>
            <span>Mi Reloj</span>
          </button>
          <button
            onClick={() => setActiveTab("apps")}
            className={`flex flex-col items-center gap-1 text-xs font-medium ${
              activeTab === "apps" ? "text-emerald-400" : "text-zinc-500"
            }`}
          >
            <span className="text-lg">🔔</span>
            <span>Apps</span>
          </button>
          <button
            onClick={() => setActiveTab("inbox")}
            className={`flex flex-col items-center gap-1 text-xs font-medium ${
              activeTab === "inbox" ? "text-emerald-400" : "text-zinc-500"
            }`}
          >
            <span className="text-lg">💬</span>
            <span>Recientes</span>
          </button>
        </div>
      </nav>
    </div>
  );
}
