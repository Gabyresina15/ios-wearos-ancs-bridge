# Atajo de iOS → Apple Health

Este atajo lee la telemetría del Galaxy Watch 6 (peso, % grasa, pasos) desde el backend y la escribe en la app **Salud** (HealthKit) del iPhone 16 Pro Max.

## Requisitos
- Backend desplegado y accesible por HTTPS (ver `docs/DEPLOY.md`).
- Reloj sincronizando telemetría (al menos una vez).
- iPhone con la app Atajos instalada.

## Paso a paso

### 1. Crear el Atajo
1. Abre la app **Atajos** en el iPhone.
2. Toca el botón `+` → **Nuevo atajo**.
3. Nombra el atajo: `Sync Watch → Salud`.

### 2. Obtener la telemetría
1. Busca la acción **Obtener contenido de URL** (o `Get Contents of URL`).
2. URL: `https://TU-DOMINIO.up.railway.app/api/telemetry/latest`
3. Método: `GET`
4. Agrega la acción **Obtener diccionario de entrada** (Get Dictionary from Input).

### 3. Extraer los valores
Agrega estas acciones en orden:

| Acción | Configuración |
|---------|---------------|
| **Obtener valor del diccionario** | Clave: `weightKg` |
| **Si** | Condición: el valor no está vacío |
| **Registrar datos de salud** | Tipo: Peso · Valor: el número extraído · Unidad: kg |
| **Fin de Si** | |

Repite el bloque para:
- Clave `bodyFatPercentage` → Tipo: Porcentaje de grasa corporal
- Clave `stepsToday` → Tipo: Pasos (opcional, HealthKit suele preferir la fuente del iPhone)

### 4. Ejemplo de flujo completo
```
Obtener contenido de URL
  URL: https://tu-backend.up.railway.app/api/telemetry/latest
Obtener diccionario de entrada

# Peso
Obtener valor del diccionario → weightKg
Si [peso] tiene valor
  Registrar datos de salud → Peso = [peso] kg
Fin de Si

# Grasa corporal
Obtener valor del diccionario → bodyFatPercentage
Si [grasa] tiene valor
  Registrar datos de salud → Porcentaje de grasa corporal = [grasa] %
Fin de Si
```

### 5. Automatizarlo
1. Toca el icono de información (i) del atajo.
2. Activa **Mostrar en la hoja de compartir** o mejor:
3. Toca **Automatización** → **Crear automatización personal**.
4. Elige **Hora del día** (por ejemplo 21:00) o **Cuando abro la app Salud**.
5. Ejecuta el atajo.
6. Desactiva “Preguntar antes de ejecutar” para que corra solo.

### 6. Permisos
La primera vez que el atajo intente escribir en Salud, iOS pedirá permiso. Acepta para **Peso** y **Porcentaje de grasa corporal**.

## Verificación
- Abre la app **Salud** → pestaña Explorar → Peso / Grasa corporal.
- Deberías ver el valor más reciente enviado por el reloj.

## Notas
- El endpoint devuelve el último snapshot que el reloj envió (por WebSocket o por `POST /api/telemetry` desde WorkManager).
- Si `weightKg` o `bodyFatPercentage` son `null`, el atajo simplemente omite esa medición.
- Puedes agregar más campos (pasos, frecuencia cardíaca) usando las mismas acciones de HealthKit.
