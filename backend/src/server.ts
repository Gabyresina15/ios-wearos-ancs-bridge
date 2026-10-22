import Fastify from "fastify";
import cors from "@fastify/cors";
import websocket, { SocketStream } from "@fastify/websocket";
import { z } from "zod";
import fs from "node:fs";
import path from "node:path";

const fastify = Fastify({ logger: true });

await fastify.register(cors, { origin: "*" });
await fastify.register(websocket);

const STATE_FILE_PATH = path.resolve(process.cwd(), "bridge-state.json");

const TelemetryPayloadSchema = z.object({
  watchBatteryPercent: z.number().int().min(0).max(100),
  isCharging: z.boolean(),
  stepsToday: z.number().int().nonnegative(),
  latestHeartRateBpm: z.number().int().nonnegative(),
  weightKg: z.number().positive().nullable(),
  bodyFatPercentage: z.number().min(0).max(100).nullable(),
  timestamp: z.number(),
});

const BatchTelemetryPayloadSchema = z.object({
  items: z.array(TelemetryPayloadSchema).min(1),
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

function getTodayDateKey(): string {
  return new Date().toISOString().slice(0, 10);
}

const bridgeState = {
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
    weightKg: null as number | null,
    bodyFatPercentage: null as number | null,
    timestamp: 0,
  } as TelemetryData,
  stepSyncWatermark: {
    lastSyncedStepsTotal: 0,
    lastSyncedDateKey: getTodayDateKey(),
    lastSyncedWeightKg: null as number | null,
    lastSyncedBodyFatPct: null as number | null,
  },
  blockedBundles: ["com.apple.tips"] as string[],
  notificationHistory: [] as AncsNotificationLog[],
};

function loadPersistedState() {
  try {
    if (fs.existsSync(STATE_FILE_PATH)) {
      const raw = fs.readFileSync(STATE_FILE_PATH, "utf-8");
      const parsed = JSON.parse(raw);
      if (parsed.telemetry) bridgeState.telemetry = parsed.telemetry;
      if (parsed.stepSyncWatermark) bridgeState.stepSyncWatermark = parsed.stepSyncWatermark;
      if (Array.isArray(parsed.blockedBundles)) bridgeState.blockedBundles = parsed.blockedBundles;
      if (Array.isArray(parsed.notificationHistory)) {
        bridgeState.notificationHistory = parsed.notificationHistory.slice(0, 50);
      }
    }
  } catch (err) {
    fastify.log.warn({ err }, "No se pudo cargar bridge-state.json previo");
  }
}

function savePersistedState() {
  try {
    const tmpPath = `${STATE_FILE_PATH}.tmp`;
    fs.writeFileSync(tmpPath, JSON.stringify(bridgeState, null, 2), "utf-8");
    fs.renameSync(tmpPath, STATE_FILE_PATH);
  } catch (err) {
    fastify.log.error({ err }, "Error guardando bridge-state.json");
  }
}

loadPersistedState();

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
  if (data.timestamp >= bridgeState.telemetry.timestamp) {
    bridgeState.telemetry = data;
    bridgeState.watchStatus.watchBatteryPercent = data.watchBatteryPercent;
    bridgeState.watchStatus.isCharging = data.isCharging;
    bridgeState.watchStatus.lastSeen = Date.now();
    savePersistedState();

    broadcastToWeb({
      type: "TELEMETRY_UPDATED",
      data: bridgeState.telemetry,
    });
  }
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

fastify.get("/api/health/consume-delta", async () => {
  const todayKey = getTodayDateKey();
  const currentTotal = bridgeState.telemetry.stepsToday;
  const watermark = bridgeState.stepSyncWatermark;

  let deltaSteps = 0;
  if (watermark.lastSyncedDateKey !== todayKey || currentTotal < watermark.lastSyncedStepsTotal) {
    deltaSteps = currentTotal;
  } else {
    deltaSteps = Math.max(0, currentTotal - watermark.lastSyncedStepsTotal);
  }

  watermark.lastSyncedStepsTotal = currentTotal;
  watermark.lastSyncedDateKey = todayKey;

  const newWeightKg =
    bridgeState.telemetry.weightKg !== watermark.lastSyncedWeightKg
      ? bridgeState.telemetry.weightKg
      : null;
  const newBodyFatPct =
    bridgeState.telemetry.bodyFatPercentage !== watermark.lastSyncedBodyFatPct
      ? bridgeState.telemetry.bodyFatPercentage
      : null;

  watermark.lastSyncedWeightKg = bridgeState.telemetry.weightKg;
  watermark.lastSyncedBodyFatPct = bridgeState.telemetry.bodyFatPercentage;

  savePersistedState();

  return {
    stepsDelta: deltaSteps,
    stepsTotalToday: currentTotal,
    latestHeartRateBpm: bridgeState.telemetry.latestHeartRateBpm,
    weightKg: newWeightKg,
    bodyFatPercentage: newBodyFatPct,
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

fastify.post("/api/telemetry/batch", async (request, reply) => {
  const parsed = BatchTelemetryPayloadSchema.safeParse(request.body);
  if (!parsed.success) {
    return reply.status(400).send({
      error: "Invalid batch telemetry payload",
      details: parsed.error.flatten(),
    });
  }

  const sorted = [...parsed.data.items].sort((a, b) => a.timestamp - b.timestamp);
  for (const item of sorted) {
    updateTelemetryState(item);
  }

  return reply.status(200).send({
    ok: true,
    processedCount: sorted.length,
    syncedAt: Date.now(),
  });
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
            savePersistedState();
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
            savePersistedState();
            broadcastToWeb({ type: "NEW_ANCS_NOTIFICATION", data: msg });
            break;

          case "REMOTE_ALERT":
          case "AMS_COMMAND":
          case "REQUEST_TELEMETRY":
            broadcastToWatch(msg);
            break;

          case "SYNC_CONFIG":
            bridgeState.blockedBundles = msg.blockedBundles;
            savePersistedState();
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
