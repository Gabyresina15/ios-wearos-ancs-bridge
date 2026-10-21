import Fastify from "fastify";
import cors from "@fastify/cors";
import websocket, { SocketStream } from "@fastify/websocket";
import { z } from "zod";

const fastify = Fastify({ logger: true });

await fastify.register(cors, { origin: "*" });
await fastify.register(websocket);

// Esquemas de validación para mensajes entrantes
const IncomingMessageSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("WATCH_STATUS"),
    state: z.string(),
    stateLabel: z.string(),
    deviceModel: z.string(),
    pairedIphone: z.string(),
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
    type: z.literal("HEALTH_SYNC"),
    stepsToday: z.number(),
    latestHeartRateBpm: z.number(),
    weightKg: z.number().nullable(),
    bodyFatPercentage: z.number().nullable(),
    timestamp: z.number(),
  }),
]);

type AncsNotificationLog = z.infer<typeof IncomingMessageSchema> & {
  type: "ANCS_NOTIFICATION";
};

// Estado en memoria del puente
const bridgeState = {
  watchStatus: {
    state: "DISCONNECTED",
    stateLabel: "Desconectado",
    deviceModel: "Galaxy Watch 6 Classic",
    pairedIphone: "iPhone 16 Pro Max",
    lastSeen: 0,
  },
  blockedBundles: ["com.apple.tips"] as string[],
  notificationHistory: [] as AncsNotificationLog[],
};

// Métricas de salud más recientes (para Apple Shortcuts / PWA)
let latestHealthMetrics = {
  stepsToday: 0,
  latestHeartRateBpm: 0,
  weightKg: null as number | null,
  bodyFatPercentage: null as number | null,
  timestamp: 0,
};

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

fastify.get("/health", async () => ({
  status: "ok",
  watchConnected: watchClients.size > 0,
  state: bridgeState.watchStatus,
  health: latestHealthMetrics,
}));

// Endpoint para el Atajo de iOS (Apple Shortcuts) → Apple Health
fastify.get("/api/health/latest", async () => {
  return latestHealthMetrics;
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
          health: latestHealthMetrics,
        })
      );
    }

    connection.socket.on("message", (raw) => {
      try {
        const parsedJson = JSON.parse(raw.toString());
        const msg = IncomingMessageSchema.parse(parsedJson);

        switch (msg.type) {
          case "WATCH_STATUS":
            bridgeState.watchStatus = {
              ...msg,
              lastSeen: Date.now(),
            };
            broadcastToWeb({ type: "WATCH_STATUS_UPDATED", data: bridgeState.watchStatus });
            break;

          case "ANCS_NOTIFICATION":
            bridgeState.notificationHistory = [
              msg,
              ...bridgeState.notificationHistory.filter((n) => n.uid !== msg.uid),
            ].slice(0, 50);
            broadcastToWeb({ type: "NEW_ANCS_NOTIFICATION", data: msg });
            break;

          case "REMOTE_ALERT":
            broadcastToWatch(msg);
            break;

          case "SYNC_CONFIG":
            bridgeState.blockedBundles = msg.blockedBundles;
            broadcastToWatch(msg);
            broadcastToWeb({ type: "CONFIG_UPDATED", blockedBundles: msg.blockedBundles });
            break;

          case "AMS_COMMAND":
            broadcastToWatch(msg);
            break;

          case "HEALTH_SYNC":
            latestHealthMetrics = {
              stepsToday: msg.stepsToday,
              latestHeartRateBpm: msg.latestHeartRateBpm,
              weightKg: msg.weightKg,
              bodyFatPercentage: msg.bodyFatPercentage,
              timestamp: msg.timestamp,
            };
            broadcastToWeb({ type: "HEALTH_UPDATED", data: latestHealthMetrics });
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
        bridgeState.watchStatus.state = "DISCONNECTED";
        bridgeState.watchStatus.stateLabel = "Watch Offline (WS)";
        broadcastToWeb({ type: "WATCH_STATUS_UPDATED", data: bridgeState.watchStatus });
      }
    });
  });
});

const PORT = Number(process.env.PORT ?? 8080);
await fastify.listen({ port: PORT, host: "0.0.0.0" });
