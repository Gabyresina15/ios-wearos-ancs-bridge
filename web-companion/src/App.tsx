import React, { useEffect, useRef, useState } from "react";

interface WatchStatus {
  state: string;
  stateLabel: string;
  deviceModel: string;
  pairedIphone: string;
  lastSeen: number;
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
    deviceModel: "Galaxy Watch 6 Classic",
    pairedIphone: "iPhone 16 Pro Max",
    lastSeen: 0,
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
          setNotifications(payload.data.notificationHistory);
          setBlockedBundles(payload.data.blockedBundles);
        } else if (payload.type === "WATCH_STATUS_UPDATED") {
          setWatchStatus(payload.data);
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
        {/* Header */}
        <header className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-zinc-900 border border-zinc-800 p-5 rounded-2xl">
          <div>
            <h1 className="text-xl font-bold tracking-tight">
              GW6 Classic ⇄ iPhone 16 Pro Max Bridge
            </h1>
            <p className="text-xs text-zinc-400 mt-1">
              Cliente BLE ANCS/AMS Nativo + Telemetría WebSocket
            </p>
          </div>
          <div className="flex items-center gap-3">
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
                wsConnected ? "bg-sky-500/20 text-sky-400" : "bg-red-500/20 text-red-400"
              }`}
            >
              {wsConnected ? "WS Online" : "WS Offline"}
            </span>
          </div>
        </header>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Emisor de Alertas y AMS */}
          <section className="bg-zinc-900 border border-zinc-800 p-5 rounded-2xl space-y-4">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-400">
              Disparo de Alerta al Reloj & Control AMS
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
            Stream de Notificaciones ANCS Recibidas ({notifications.length})
          </h2>
          <div className="space-y-2 max-h-96 overflow-y-auto">
            {notifications.map((n) => (
              <div
                key={n.uid}
                className="flex items-start justify-between bg-zinc-950 border border-zinc-800/80 p-3 rounded-xl"
              >
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-emerald-400">{n.appDisplayName}</span>
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
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
