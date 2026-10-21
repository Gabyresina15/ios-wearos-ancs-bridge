# Despliegue en Producción

## 1. Backend (Railway / Render / Fly.io)

### Railway (recomendado)
1. Crea una cuenta en [railway.app](https://railway.app) y un nuevo proyecto.
2. Conecta el repo `Gabyresina15/ios-wearos-ancs-bridge`.
3. En el servicio, configura:
   - **Root Directory**: `backend`
   - **Build Command**: (dejar vacío, usa el Dockerfile)
   - **Start Command**: `node dist/server.js`
4. Variables de entorno (opcional):
   - `PORT` → Railway lo inyecta automáticamente
   - `NODE_ENV=production`
5. Genera un dominio público (Settings → Networking → Generate Domain).

Obtendrás algo como:
- HTTPS: `https://tu-proyecto.up.railway.app`
- WSS: `wss://tu-proyecto.up.railway.app/ws?role=watch`

### Variables que debes actualizar en el reloj
En `watch-app/build.gradle.kts` cambia:

```kotlin
buildConfigField("String", "WS_BACKEND_URL", "\"wss://TU-DOMINIO.up.railway.app/ws?role=watch\"")
buildConfigField("String", "HTTP_BACKEND_URL", "\"https://TU-DOMINIO.up.railway.app\"")
```

Recompila e instala el APK.

### Alternativas
- **Render**: New Web Service → Docker → Root Directory `backend`.
- **Fly.io**: `fly launch` dentro de `backend/` (usa el Dockerfile existente).

---

## 2. Web Companion (Vercel / Netlify)

### Vercel
1. Importa el repo en [vercel.com](https://vercel.com).
2. Configura:
   - **Root Directory**: `web-companion`
   - **Framework Preset**: Vite
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
3. Variables de entorno:
   - `VITE_WS_URL` = `wss://TU-DOMINIO.up.railway.app/ws?role=web`
4. Deploy.

### Netlify
1. New site from Git → selecciona el repo.
2. Base directory: `web-companion`
3. Build command: `npm run build`
4. Publish directory: `web-companion/dist`
5. Variable: `VITE_WS_URL` igual que arriba.

---

## 3. Verificación
- Backend: `curl https://TU-DOMINIO.up.railway.app/health`
- PWA: abre la URL de Vercel y verifica que el badge "WS Online" se ponga verde cuando el reloj esté conectado.
- Reloj: debe aparecer en el header de la PWA con estado BLE + batería.

## 4. CORS y seguridad
El backend actual tiene `cors: { origin: "*" }` (adecuado para desarrollo). Para producción puedes restringirlo al dominio de Vercel si lo deseas.
