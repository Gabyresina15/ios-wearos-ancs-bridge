# Samsung Health Monitor MOD (ECG + Presión Arterial)

El Galaxy Watch 6 Classic tiene sensores de ECG y presión arterial, pero Samsung los bloquea en relojes no vinculados a teléfonos Samsung. El **Samsung Health Monitor MOD** (mantenido por Dante63) desbloquea estas funciones para que funcionen con cualquier teléfono, incluido el iPhone 16 Pro Max.

> **Advertencia**: Este procedimiento modifica el comportamiento del reloj. Úsalo bajo tu responsabilidad. No reemplaza un dispositivo médico certificado.

## Requisitos
- Galaxy Watch 6 Classic con Wear OS 4+.
- ADB inalámbrico funcionando (ver README principal).
- APK de Samsung Health Monitor MOD (versión compatible con Watch 6).

## 1. Descargar el MOD
Busca el último release de **Samsung Health Monitor MOD** por Dante63 (comunidad XDA / Telegram).
Archivos típicos:
- `SHM_MOD_WearOS.apk` (para el reloj)
- A veces un companion para el teléfono (no necesario si solo quieres medir en el reloj).

## 2. Instalar en el reloj vía ADB
```bash
# Conectado por ADB inalámbrico
adb -s IP:PUERTO install -r SHM_MOD_WearOS.apk
```

Si da error de firma o versión:
```bash
adb -s IP:PUERTO uninstall com.samsung.android.shealthmonitor
adb -s IP:PUERTO install -r SHM_MOD_WearOS.apk
```

## 3. Permisos necesarios
```bash
adb -s IP:PUERTO shell pm grant com.samsung.android.shealthmonitor android.permission.BODY_SENSORS
adb -s IP:PUERTO shell pm grant com.samsung.android.shealthmonitor android.permission.ACTIVITY_RECOGNITION
```

## 4. Configuración en el reloj
1. Abre **Samsung Health Monitor** en el reloj.
2. Completa el asistente inicial (puede pedir calibración de presión arterial con un tensómetro externo la primera vez).
3. Para ECG: coloca el dedo en el botón superior mientras el reloj está quieto.
4. Para presión arterial: sigue las instrucciones en pantalla (generalmente quieto + brazo a la altura del corazón).

## 5. Uso junto al puente ANCS
El MOD y el puente ANCS conviven sin conflicto:
- El puente maneja notificaciones, llamadas HFP y telemetría básica (pasos, FC, peso, grasa).
- Samsung Health Monitor MOD maneja ECG y presión arterial de forma independiente.

Puedes abrir ambas apps desde el cajón de aplicaciones del reloj.

## 6. Exportar mediciones (opcional)
Algunas versiones del MOD permiten exportar ECG/BP a archivos o sincronizar con Samsung Health (si tienes un teléfono Android secundario). Con iPhone la exportación suele ser manual (captura de pantalla o archivo generado).

## Problemas comunes
| Problema | Solución |
|----------|---------|
| "Esta función no está disponible en tu región" | El MOD suele bypassear esto. Si persiste, revisa la versión del APK. |
| ECG no detecta contacto | Limpia el sensor trasero y el botón superior. Mantén el dedo quieto 30s. |
| Presión arterial pide calibración | Necesitas un tensómetro de brazo. Calibra una vez; después puede medir sin él (con menor precisión). |
| La app se cierra sola | Reinstala el APK y otorga permisos de nuevo. |

## Referencias
- Comunidad Dante63 (XDA Developers / Telegram).
- Verifica siempre la versión del MOD compatible con Galaxy Watch 6 / Wear OS 4.
