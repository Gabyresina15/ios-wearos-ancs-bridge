import React, { useEffect, useRef, useState } from "react";

interface WatchStatus {
  state: string;
  stateLabel: string;
  hfpConnected: boolean;
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

const WS_URL = import.meta.env.VITE_WS_URL || "ws://localhost:8080/ws?role=web";

export default function App() {
  const wsRef = useRef<WebSocket | null>(null);
  const [wsConnected, setWsConnected] = useState(false);
  const [watchStatus, setWatchStatus] = useState<WatchStatus>({
    state: "DISCONNECTED",
    stateLabel: "Desconectado",
    hfpConnected: false,
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
  const [newBundleInput, setNewBundleInput] = useState("");
  const [alertTitle, setAlertTitle] = useState("Alerta de Prueba");
  const [alertMessage, setAlertMessage] = useState("Ping directo desde PWA al Galaxy Watch 6");

  useEffect(() => {
    let reconnectTimer: number;
    const connect = () => {
      const ws = new WebSocket(WS_URL);
      wsRef.current = ws;

      ws.onopen = () => setWsConnected(true);
      ws.onclose = () => {
        setWsConnected(false);
        reconnectTimer = window.setTimeout(connect, 3000);
      };

      ws.onmessage = (event) => {
        const payload = JSON.parse(event.data);
        if (payload.type === "INITIAL_SNAPSHOT") {
          setWatchStatus(payload.data.watchStatus);
          setTelemetry(payload.data.telemetry);
          setNotifications(payload.data.notificationHistory);
          setBlockedBundles(payload.data.blockedBundles);
        } else if (payload.type === "WATCH_STATUS_UPDATED") {
          setWatchStatus(payload.data);
        } else if (payload.type === "TELEMETRY_UPDATED") {
          setTelemetry(payload.data);
        } else if (payload.type === "NEW_ANCS_NOTIFICATION") {
          setNotifications((prev) =>
            [payload.data, ...prev.filter((n) => n.uid !== payload.data.uid)].slice(0, 50)
          );
        } else if (payload.type === "CONFIG_UPDATED") {
          setBlockedBundles(payload.blockedBundles);
        }
      };
    };

    connect();
    return () => {
      clearTimeout(reconnectTimer);
      wsRef.current?.close();
    };
  }, []);

  const sendTestAlert = (e: React.FormEvent) => {
    e.preventDefault();
    wsRef.current?.send(
      JSON.stringify({
        type: "REMOTE_ALERT",
        title: alertTitle,
        message: alertMessage,
      })
    );
  };

  const requestTelemetry = () => {
    wsRef.current?.send(JSON.stringify({ type: "REQUEST_TELEMETRY" }));
  };

  const toggleBlockBundle = (bundleId: string) => {
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

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 p-6 font-sans">
      <div className="max-w-4xl mx-auto space-y-6">
        {/* Header con Estado BLE y HFP */}
        <header className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-zinc-900 border border-zinc-800 p-5 rounded-2xl">
          <div>
            <h1 className="text-xl font-bold tracking-tight">
              GW6 Classic ⇄ iPhone 16 Pro Max Bridge
            </h1>
            <p className="text-xs text-zinc-400 mt-1">
              ANCS + AMS + HFP (Llamadas) + Health Connect
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span
              className={`px-3 py-1 rounded-full text-xs font-semibold ${
                watchStatus.state === "READY"
                  ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                  : "bg-amber-500/20 text-amber-400 border border-amber-500/30"
              }`}
            >
              BLE: {watchStatus.stateLabel}
            </span>
            <span
              className={`px-3 py-1 rounded-full text-xs font-semibold ${
                watchStatus.hfpConnected
                  ? "bg-indigo-500/20 text-indigo-400 border border-indigo-500/30"
                  : "bg-zinc-800 text-zinc-400 border border-zinc-700"
              }`}
            >
              HFP: {watchStatus.hfpConnected ? "Voz Activa" : "Standby"}
            </span>
            <span
              className={`px-3 py-1 rounded-full text-xs font-semibold ${
                wsConnected ? "bg-sky-500/20 text-sky-400" : "bg-red-500/20 text-red-400"
              }`}
            >
              {wsConnected ? "WS Online" : "WS Offline"}
            </span>
          </div>
        </header>

        {/* Tarjetas de Telemetría (Batería y Salud) */}
        <section className="bg-zinc-900 border border-zinc-800 p-5 rounded-2xl space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400">
              Telemetría y Salud del Reloj
            </h2>
            <button
              onClick={requestTelemetry}
              className="bg-zinc-800 hover:bg-zinc-700 text-xs px-3 py-1 rounded-lg transition"
            >
              Actualizar Ahora 🔄
            </button>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div className="bg-zinc-950 border border-zinc-800 p-3.5 rounded-xl">
              <span className="text-xs text-zinc-400">Batería Reloj</span>
              <p className="text-lg font-bold mt-1">
                {telemetry.watchBatteryPercent}%{" "}
                {telemetry.isCharging && <span className="text-emerald-400 text-xs">⚡ Carga</span>}
              </p>
            </div>
            <div className="bg-zinc-950 border border-zinc-800 p-3.5 rounded-xl">
              <span className="text-xs text-zinc-400">Pasos Hoy</span>
              <p className="text-lg font-bold mt-1 text-emerald-400">
                {telemetry.stepsToday.toLocaleString()} 👣
              </p>
            </div>
            <div className="bg-zinc-950 border border-zinc-800 p-3.5 rounded-xl">
              <span className="text-xs text-zinc-400">Frecuencia Cardíaca</span>
              <p className="text-lg font-bold mt-1 text-rose-400">
                {telemetry.latestHeartRateBpm} bpm ❤️
              </p>
            </div>
            <div className="bg-zinc-950 border border-zinc-800 p-3.5 rounded-xl">
              <span className="text-xs text-zinc-400">BIA (Peso / Grasa)</span>
              <p className="text-sm font-bold mt-1 text-sky-400">
                {telemetry.weightKg ? `${telemetry.weightKg} kg` : "--"} /{" "}
                {telemetry.bodyFatPercentage ? `${telemetry.bodyFatPercentage}%` : "--"}
              </p>
            </div>
          </div>
        </section>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Emisor de Alertas y AMS */}
          <section className="bg-zinc-900 border border-zinc-800 p-5 rounded-2xl space-y-4">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400">
              Disparo de Alerta & Control AMS
            </h2>
            <form onSubmit={sendTestAlert} className="space-y-3">
              <input
                type="text"
                value={alertTitle}
                onChange={(e) => setAlertTitle(e.target.value)}
                placeholder="Título"
                className="w-full bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-sm"
              />
              <textarea
                value={alertMessage}
                onChange={(e) => setAlertMessage(e.target.value)}
                placeholder="Mensaje"
                rows={2}
                className="w-full bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-sm"
              />
              <button
                type="submit"
                className="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-semibold py-2 rounded-xl text-sm transition"
              >
                Enviar Alerta vía WebSocket al Reloj
              </button>
            </form>

            <div className="pt-3 border-t border-zinc-800">
              <p className="text-xs text-zinc-400 mb-2">Apple Media Service (AMS Proxy):</p>
              <div className="grid grid-cols-3 gap-2">
                <button
                  onClick={() => sendMediaCommand("PREVIOUS_TRACK")}
                  className="bg-zinc-800 hover:bg-zinc-700 py-2 rounded-lg text-xs"
                >
                  ⏮ Anterior
                </button>
                <button
                  onClick={() => sendMediaCommand("TOGGLE_PLAY_PAUSE")}
                  className="bg-zinc-800 hover:bg-zinc-700 py-2 rounded-lg text-xs"
                >
                  ⏯ Play/Pause
                </button>
                <button
                  onClick={() => sendMediaCommand("NEXT_TRACK")}
                  className="bg-zinc-800 hover:bg-zinc-700 py-2 rounded-lg text-xs"
                >
                  ⏭ Siguiente
                </button>
              </div>
            </div>
          </section>

          {/* Filtro de Bundle IDs de iOS */}
          <section className="bg-zinc-900 border border-zinc-800 p-5 rounded-2xl space-y-4">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400">
              Filtro de Bundle IDs (Lista Negra ANCS)
            </h2>
            <div className="flex gap-2">
              <input
                type="text"
                value={newBundleInput}
                onChange={(e) => setNewBundleInput(e.target.value)}
                placeholder="Ej. com.burbn.instagram"
                className="flex-1 bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-sm"
              />
              <button
                onClick={() => {
                  if (newBundleInput.trim()) {
                    toggleBlockBundle(newBundleInput.trim());
                    setNewBundleInput("");
                  }
                }}
                className="bg-zinc-800 hover:bg-zinc-700 px-4 rounded-xl text-sm font-medium"
              >
                Bloquear
              </button>
            </div>
            <div className="flex flex-wrap gap-2">
              {blockedBundles.map((bundle) => (
                <span
                  key={bundle}
                  onClick={() => toggleBlockBundle(bundle)}
                  className="cursor-pointer bg-red-500/15 border border-red-500/30 text-red-300 text-xs px-3 py-1 rounded-full"
                >
                  {bundle} ✕
                </span>
              ))}
            </div>
          </section>
        </div>

        {/* Stream de notificaciones ANCS en vivo */}
        <section className="bg-zinc-900 border border-zinc-800 p-5 rounded-2xl space-y-3">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400">
            Stream de Notificaciones y Llamadas ANCS ({notifications.length})
          </h2>
          <div className="space-y-2 max-h-96 overflow-y-auto">
            {notifications.map((n) => {
              const isCall = n.appIdentifier === "com.apple.mobilephone";
              return (
                <div
                  key={n.uid}
                  className={`flex items-start justify-between border p-3 rounded-xl ${
                    isCall
                      ? "bg-emerald-950/20 border-emerald-500/30"
                      : "bg-zinc-950 border-zinc-800/80"
                  }`}
                >
                  <div>
                    <div className="flex items-center gap-2">
                      <span
                        className={`text-xs font-bold ${
                          isCall ? "text-emerald-400" : "text-emerald-400"
                        }`}
                      >
                        {n.appDisplayName}
                      </span>
                      <span className="text-[11px] text-zinc-500 font-mono">({n.appIdentifier})</span>
                    </div>
                    <p className="text-sm font-semibold mt-0.5">{n.title}</p>
                    <p className="text-xs text-zinc-400">{n.message || n.subtitle}</p>
                  </div>
                  <button
                    onClick={() => toggleBlockBundle(n.appIdentifier)}
                    className="text-[11px] text-zinc-400 hover:text-red-400 border border-zinc-800 px-2 py-1 rounded-lg"
                  >
                    Silenciar App
                  </button>
                </div>
              );
            })}
          </div>
        </section>
      </div>
    </div>
  );
}
