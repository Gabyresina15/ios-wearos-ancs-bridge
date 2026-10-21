# iOS Wear OS ANCS Bridge

Monorepo para un puente BLE nativo entre Galaxy Watch 6 Classic (Wear OS 4+) y iPhone 16 Pro Max usando ANCS/AMS, sin necesidad de app en iOS.

## Estructura

- `watch-app/`: App nativa Kotlin para Wear OS (Dual-Role BLE: Peripheral + Central)
- `backend/`: Node.js + Fastify + WebSockets + Zod (hub de sincronización)
- `web-companion/`: PWA React + Vite + Tailwind (panel de control y telemetría)

## Guía de Aprovisionamiento y Emparejamiento

### Fase 1: Preparar el Galaxy Watch 6 Classic
Si el reloj está recién restaurado de fábrica:
1. Mantén presionado el logo de bienvenida ~5s o usa el modo standalone / vincula brevemente a un Android para pasar el wizard.
2. En el reloj: Ajustes > Acerca del reloj > Información de software → toca 7 veces "Versión de software" para activar Opciones de desarrollador.

### Fase 2: Compilación e Instalación vía ADB Inalámbrico
1. Misma red Wi-Fi (2.4 GHz).
2. En el reloj: Ajustes > Opciones de desarrollador → Depuración por ADB + Depuración inalámbrica → Vincular dispositivo nuevo.
3. Anota IP:Puerto de pairing y el código de 6 dígitos.
4. Desde la raíz del monorepo:

```bash
# Emparejar
adb pair 192.168.1.45:39123   # ingresa el código

# Conectar al puerto de conexión (el que muestra "Depuración inalámbrica")
adb connect 192.168.1.45:41555

# Compilar e instalar
cd watch-app
./gradlew assembleDebug
adb -s 192.168.1.45:41555 install -r build/outputs/apk/debug/watch-app-debug.apk

# Permisos
adb -s 192.168.1.45:41555 shell pm grant com.ancsbridge.watch android.permission.BLUETOOTH_SCAN
adb -s 192.168.1.45:41555 shell pm grant com.ancsbridge.watch android.permission.BLUETOOTH_CONNECT
adb -s 192.168.1.45:41555 shell pm grant com.ancsbridge.watch android.permission.BLUETOOTH_ADVERTISE
adb -s 192.168.1.45:41555 shell pm grant com.ancsbridge.watch android.permission.POST_NOTIFICATIONS

# Excluir de Doze
adb -s 192.168.1.45:41555 shell dumpsys deviceidle whitelist +com.ancsbridge.watch
```

### Fase 3: Emparejamiento nativo con iPhone
1. Abre la app en el reloj → estado "Esperando iPhone..." (advertising con Service Solicitation ANCS).
2. En iPhone: Ajustes > Bluetooth → Otros dispositivos → "GW6-ANCS-Bridge".
3. Acepta el enlace Bluetooth.
4. Acepta el diálogo "¿Permitir que GW6-ANCS-Bridge reciba las notificaciones de tu iPhone?".
5. Verifica en Ajustes > Bluetooth > (i) junto al dispositivo que "Compartir notificaciones del sistema" esté activado.
6. El reloj debe pasar a "ANCS + AMS Activo".

## Backend

```bash
cd backend
npm install
npm run dev
# o docker-compose up --build desde la raíz
```

## Web Companion

```bash
cd web-companion
npm install
npm run dev
# Configura VITE_WS_URL si el backend no está en localhost:8080
```

El reloj se conecta automáticamente al WebSocket del backend para telemetría, filtros de Bundle ID y alertas remotas.
