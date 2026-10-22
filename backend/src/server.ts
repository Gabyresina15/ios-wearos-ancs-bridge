import Fastify from "fastify";
import cors from "@fastify/cors";
import websocket, { SocketStream } from "@fastify/websocket";
import { z } from "zod";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const fastify = Fastify({ logger: true });

await fastify.register(cors, { origin: "*" });
await fastify.register(websocket);

const DATA_FILE = join(process.cwd(), "bridge-state.json");

const TelemetryPayloadSchema = z.object({
  watchBatteryPercent: z.number().int().min(0).max(100),
  isCharging: z.boolean(),
  stepsToday: z.number().int().nonnegative(),
  latestHeartRateBpm: z.number().int().nonnegative(),
  weightKg: z.number().positive().nullable(),
  bodyFatPercentage: z.number().min(0).max(100).nullable(),
  timestamp: z.number(),
});

const IncomingWsMessageSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("WATCH_STATUS"),
    state: z.string(),
    stateLabel: z.string(),
    hfpConnected: z.boolean().optional().default(false),
    panConnected: z.boolean().optional().default(false),
    watchBatteryPercent: z.number().int().min(0).max(100).optional().default(0),
    isCharging: z.boolean().optional().default(false),
    deviceModel: z.string(),
    pairedIphone: z.string(),
  }),
  z.object({
    type: z.literal("TELEMETRY_SYNC"),
    watchBatteryPercent: z.number().int().min(0).max(100),
    isCharging: z.boolean(),
    stepsToday: z.number().int().nonnegative(),
    latestHeartRateBpm: z.number().int().nonnegative(),
    weightKg: z.number().positive().nullable(),
    bodyFatPercentage: z.number().min(0).max(100).nullable(),
    timestamp: z.number(),
  }),
  z.object({
    type: z.literal("ANCS_NOTIFICATION"),
    uid: z.number(),
    appIdentifier: z.string(),
    appDisplayName: z.string(),
    title: z.string(),
    subtitle: z.string(),
    message: z.string(),
    timestamp: z.number(),
  }),
  z.object({
    type: z.literal("REMOTE_ALERT"),
    title: z.string().min(1),
    message: z.string().min(1),
  }),
  z.object({
    type: z.literal("SYNC_CONFIG"),
    blockedBundles: z.array(z.string()),
  }),
  z.object({
    type: z.literal("AMS_COMMAND"),
    command: z.enum([
      "PLAY",
      "PAUSE",
      "TOGGLE_PLAY_PAUSE",
      "NEXT_TRACK",
      "PREVIOUS_TRACK",
      "VOLUME_UP",
      "VOLUME_DOWN",
    ]),
  }),
  z.object({
    type: z.literal("REQUEST_TELEMETRY"),
  }),
]);

type AncsNotificationLog = Extract<
  z.infer<typeof IncomingWsMessageSchema>,
  { type: "ANCS_NOTIFICATION" }
>;

type TelemetryData = z.infer<typeof TelemetryPayloadSchema>;

interface BridgeState {
  watchStatus: {
    state: string;
    stateLabel: string;
    hfpConnected: boolean;
    panConnected: boolean;
    watchBatteryPercent: number;
    isCharging: boolean;
    deviceModel: string;
    pairedIphone: string;
    lastSeen: number;
  };
  telemetry: TelemetryData;
  blockedBundles: string[];
  notificationHistory: AncsNotificationLog[];
  lastSyncedStepsTotal: number;
}

const defaultState: BridgeState = {
  watchStatus: {
    state: "DISCONNECTED",
    stateLabel: "Desconectado",
    hfpConnected: false,
    panConnected: false,
    watchBatteryPercent: 0,
    isCharging: false,
    deviceModel: "Galaxy Watch 6 Classic",
    pairedIphone: "iPhone 16 Pro Max",
    lastSeen: 0,
  },
  telemetry: {
    watchBatteryPercent: 0,
    isCharging: false,
    stepsToday: 0,
    latestHeartRateBpm: 0,
    weightKg: null,
    bodyFatPercentage: null,
    timestamp: 0,
  },
  blockedBundles: ["com.apple.tips"],
  notificationHistory: [],
  lastSyncedStepsTotal: 0,
};

function loadState(): BridgeState {
  try {
    if (existsSync(DATA_FILE)) {
      const raw = readFileSync(DATA_FILE, "utf-8");
      const parsed = JSON.parse(raw);
      return { ...defaultState, ...parsed };
    }
  } catch (err) {
    fastify.log.warn({ err }, "No se pudo cargar bridge-state.json, usando defaults");
  }
  return structuredClone(defaultState);
}

function saveState() {
  try {
    writeFileSync(DATA_FILE, JSON.stringify(bridgeState, null, 2), "utf-8");
  } catch (err) {
    fastify.log.error({ err }, "Error guardando bridge-state.json");
  }
}

const bridgeState = loadState();

const watchClients = new Set<SocketStream["socket"]>();
const webClients = new Set<SocketStream["socket"]>();

function broadcastToWeb(payload: unknown) {
  const serialized = JSON.stringify(payload);
  for (const client of webClients) {
    if (client.readyState === 1) client.send(serialized);
  }
}

function broadcastToWatch(payload: unknown) {
  const serialized = JSON.stringify(payload);
  for (const client of watchClients) {
    if (client.readyState === 1) client.send(serialized);
  }
}

function updateTelemetryState(data: TelemetryData) {
  bridgeState.telemetry = data;
  bridgeState.watchStatus.watchBatteryPercent = data.watchBatteryPercent;
  bridgeState.watchStatus.isCharging = data.isCharging;
  bridgeState.watchStatus.lastSeen = Date.now();
  saveState();

  broadcastToWeb({
    type: "TELEMETRY_UPDATED",
    data: bridgeState.telemetry,
  });
}

fastify.get("/health", async () => ({
  status: "ok",
  watchConnectedWs: watchClients.size > 0,
  watchStatus: bridgeState.watchStatus,
  telemetry: bridgeState.telemetry,
}));

fastify.get("/api/telemetry/latest", async () => {
  return bridgeState.telemetry;
});

// Endpoint para Atajo iOS: solo los pasos nuevos desde la última consulta
fastify.get("/api/health/consume-delta", async () => {
  const currentTotal = bridgeState.telemetry.stepsToday;
  const deltaSteps = Math.max(0, currentTotal - bridgeState.lastSyncedStepsTotal);
  bridgeState.lastSyncedStepsTotal = currentTotal;
  saveState();

  return {
    stepsDelta: deltaSteps,
    stepsTotalToday: currentTotal,
    latestHeartRateBpm: bridgeState.telemetry.latestHeartRateBpm,
    weightKg: bridgeState.telemetry.weightKg,
    bodyFatPercentage: bridgeState.telemetry.bodyFatPercentage,
    timestamp: Date.now(),
  };
});

fastify.post("/api/telemetry", async (request, reply) => {
  const parsed = TelemetryPayloadSchema.safeParse(request.body);
  if (!parsed.success) {
    return reply.status(400).send({
      error: "Invalid telemetry payload",
      details: parsed.error.flatten(),
    });
  }

  updateTelemetryState(parsed.data);
  return reply.status(200).send({ ok: true, syncedAt: Date.now() });
});

fastify.register(async (app) => {
  app.get("/ws", { websocket: true }, (connection, req) => {
    const query = req.query as { role?: string };
    const role = query.role === "watch" ? "watch" : "web";

    if (role === "watch") {
      watchClients.add(connection.socket);
      connection.socket.send(
        JSON.stringify({
          type: "SYNC_CONFIG",
          blockedBundles: bridgeState.blockedBundles,
        })
      );
    } else {
      webClients.add(connection.socket);
      connection.socket.send(
        JSON.stringify({
          type: "INITIAL_SNAPSHOT",
          data: bridgeState,
        })
      );
    }

    connection.socket.on("message", (raw) => {
      try {
        const parsedJson = JSON.parse(raw.toString());
        const msg = IncomingWsMessageSchema.parse(parsedJson);

        switch (msg.type) {
          case "WATCH_STATUS":
            bridgeState.watchStatus = {
              ...msg,
              lastSeen: Date.now(),
            };
            saveState();
            broadcastToWeb({
              type: "WATCH_STATUS_UPDATED",
              data: bridgeState.watchStatus,
            });
            break;

          case "TELEMETRY_SYNC":
            updateTelemetryState({
              watchBatteryPercent: msg.watchBatteryPercent,
              isCharging: msg.isCharging,
              stepsToday: msg.stepsToday,
              latestHeartRateBpm: msg.latestHeartRateBpm,
              weightKg: msg.weightKg,
              bodyFatPercentage: msg.bodyFatPercentage,
              timestamp: msg.timestamp,
            });
            break;

          case "ANCS_NOTIFICATION":
            bridgeState.notificationHistory = [
              msg,
              ...bridgeState.notificationHistory.filter((n) => n.uid !== msg.uid),
            ].slice(0, 50);
            saveState();
            broadcastToWeb({ type: "NEW_ANCS_NOTIFICATION", data: msg });
            break;

          case "REMOTE_ALERT":
          case "AMS_COMMAND":
          case "REQUEST_TELEMETRY":
            broadcastToWatch(msg);
            break;

          case "SYNC_CONFIG":
            bridgeState.blockedBundles = msg.blockedBundles;
            saveState();
            broadcastToWatch(msg);
            broadcastToWeb({
              type: "CONFIG_UPDATED",
              blockedBundles: msg.blockedBundles,
            });
            break;
        }
      } catch (err) {
        fastify.log.error({ err }, "Payload WebSocket inválido");
      }
    });

    connection.socket.on("close", () => {
      watchClients.delete(connection.socket);
      webClients.delete(connection.socket);
      if (role === "watch" && watchClients.size === 0) {
        bridgeState.watchStatus.state = "SLEEP_OR_DISCONNECTED";
        bridgeState.watchStatus.stateLabel = "En reposo Doze / Sin WS activo";
        saveState();
        broadcastToWeb({
          type: "WATCH_STATUS_UPDATED",
          data: bridgeState.watchStatus,
        });
      }
    });
  });
});

const PORT = Number(process.env.PORT ?? 8080);
await fastify.listen({ port: PORT, host: "0.0.0.0" });
