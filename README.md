# ABrowser: navegador para Android con uBlock Origin integrado

*Desarrollado por ASSBOXER.*

Un navegador mínimo hecho sobre **GeckoView**, el motor de Firefox. Lo usamos porque
es el único motor embebible en Android que ejecuta extensiones reales, así que
**uBlock Origin viene adentro del APK** (la extensión oficial, no una imitación con listas).

## Qué trae

- **uBlock Origin integrado**: funciona desde el primer arranque, sin instalar nada.
  - Botón del escudo: abre el panel de uBO (activar/desactivar por sitio, selector de elementos, registro).
  - Contador de bloqueos sobre el escudo.
  - Mantener presionado el escudo, o *Menú → Configuración de uBlock Origin*: abre el panel completo
    (listas de filtros, reglas propias, etc.).
  - Las listas de filtros se actualizan solas, como en Firefox.
- **Vista de escritorio** por pestaña (*Menú → Vista de escritorio*), y opción para que las pestañas
  nuevas se abran así (*Ajustes*).
- **Agente de usuario**: Firefox Android (predeterminado), Chrome Android, Safari iPhone,
  Chrome Windows, Firefox Windows, Safari macOS o uno **personalizado**.
- Pestañas (tocar el contador; mantenerlo presionado abre una nueva).
- Descargas a la carpeta *Descargas*, pantalla completa para videos, menú al mantener presionado
  sobre enlaces e imágenes, subida de archivos, diálogos de las páginas (alert, `<select>`, fechas,
  autenticación HTTP), permisos de cámara, micrófono y ubicación (los pide en el momento).
- Buscador a elección: DuckDuckGo, Google, Bing, Brave o Startpage.
- Borrar cookies, caché y sesiones sin perder la configuración de uBO.
- Se puede elegir como navegador predeterminado de Android.
- Tema claro/oscuro según el sistema.

Sin telemetría, sin cuentas, sin librerías extra: solo GeckoView y el framework de Android.

## Cómo obtener el APK (sin instalar nada en tu PC)

1. Creá un repositorio en GitHub (puede ser privado) y subí esta carpeta.
2. GitHub Actions compila solo. Entrá a **Actions → Compilar APK → la última ejecución**
   y descargá el artefacto **ABrowser-APK**.
3. Adentro hay un APK por arquitectura. Casi todos los celulares actuales usan
   **`ABrowser-arm64-v8a-release.apk`**. Si es muy viejo (32 bits), usá `armeabi-v7a`.
4. Pasalo al celular e instalalo (hay que permitir "instalar apps desconocidas").

> **Firma:** si no configurás una clave propia, cada compilación en GitHub se firma con una clave
> de depuración distinta y para actualizar tenés que desinstalar primero. Para evitarlo, generá
> una clave (`keytool -genkeypair -v -keystore liviano.jks -alias liviano -keyalg RSA -keysize 4096 -validity 10000`)
> y cargá en *Settings → Secrets → Actions*: `KEYSTORE_BASE64` (el .jks en base64),
> `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`.

## Compilar en tu PC

Requiere Android Studio (o el SDK de Android) y JDK 17.

```bash
./gradlew assembleRelease
# APKs en app/build/outputs/apk/release/
```

La primera compilación descarga uBlock Origin desde addons.mozilla.org y lo guarda en
`app/src/main/assets/extensions/ublock/`. **Para actualizar uBO** a una versión nueva, borrá esa
carpeta y volvé a compilar (las listas de filtros se actualizan solas, no hace falta recompilar).

## Tamaño

El motor Gecko es lo que pesa: el APK de arm64 ronda los 95 MB. La app en sí agrega
unos pocos cientos de KB. No hay forma de tener uBlock Origin real con un motor más chico:
el WebView del sistema (Chrome) no admite extensiones.

## Estructura

```
app/src/main/java/ar/liviano/browser/
  App.kt           Motor Gecko único + carga de uBlock Origin como extensión incorporada
  MainActivity.kt  Interfaz, pestañas, escritorio, agente, panel de uBO, descargas, permisos
  Prompts.kt       Diálogos que piden las páginas
  Prefs.kt         Ajustes, buscadores y lista de agentes de usuario
  Tab.kt           Estado de cada pestaña
```

- **Cambiar o agregar agentes de usuario:** `Prefs.kt`, objeto `UserAgents`.
- **Fijar la versión de GeckoView:** `gradle.properties` (`geckoviewVersion`). Por defecto
  usa la última estable.

## Licencias

uBlock Origin es de Raymond Hill y se distribuye bajo GPLv3. GeckoView está bajo MPL 2.0.
Si publicás el APK, incluí esos avisos.
