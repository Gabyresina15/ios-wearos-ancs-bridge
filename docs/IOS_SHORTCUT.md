# Atajo iOS — Sincronización a Apple Health

## Objetivo
Pasar pasos, frecuencia cardíaca y composición corporal del Galaxy Watch 6 a Apple Health de forma automática.

## Endpoint
`GET https://ios-wearos-ancs-bridge.onrender.com/api/telemetry/latest`

## Automatización recomendada
1. **Al conectar Bluetooth** del reloj (evento "Bluetooth" en Atajos).
2. **Cada noche a las 23:30** (automatización por hora).

## Acciones del Atajo
1. Obtener contenido de URL → endpoint de arriba.
2. Analizar JSON.
3. Registrar en Salud:
   - Pasos (`stepsToday`)
   - Frecuencia cardíaca (`latestHeartRateBpm`)
   - Peso (`weightKg`)
   - Porcentaje de grasa (`bodyFatPercentage`)

## Notas
- El backend ya calcula el delta de pasos para evitar duplicados.
- Si Render está dormido, el primer request puede tardar ~40 s. El Atajo debe tener un timeout generoso.
