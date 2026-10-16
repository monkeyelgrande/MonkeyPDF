# MonkeyPDF

Lector de PDF para Android: ligero, sin anuncios y sin cuentas. Abre los PDF que recibes por WhatsApp, correo o desde el gestor de archivos, y puede usarse como lector predeterminado del teléfono.

## Funciones

- **Abrir desde cualquier app**: aparece en «Abrir con» y «Compartir» (WhatsApp, Gmail, gestor de archivos…).
- **Recientes y favoritos**, con la última página leída de cada documento.
- **Búsqueda de texto** dentro del documento y **selección/copia** de texto.
- **Marcadores**, **índice** del documento y **miniaturas** de páginas.
- **Zoom** y desplazamiento rápido.
- **Modo noche** para leer a oscuras.
- **PDF protegidos con contraseña**.
- **Imprimir**, **compartir**, **guardar una copia** o abrir con otra app.
- **Escáner de documentos**: recorte automático y filtros de mejora, guardado como PDF.

## Requisitos

- Android 8.0 (API 26) o superior.
- El escáner necesita los servicios de Google Play actualizados (la primera vez descarga el componente).

## Descarga

El APK listo para instalar está en la sección [Releases](../../releases) del repositorio.

## Compilar desde el código

Necesitas JDK 17 y el SDK de Android.

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Tecnologías

- Kotlin + AndroidX + Material Components
- [PdfiumAndroid](https://github.com/legere/pdfiumandroid) (motor PDFium de Chrome) para renderizado, texto, búsqueda, contraseñas e índice
- [ML Kit Document Scanner](https://developers.google.com/ml-kit/vision/doc-scanner) de Google para el escáner

## Créditos

- **Autor y desarrollador:** [monkeyelgrande](https://github.com/monkeyelgrande)
- **Asistente de IA:** Claude (Anthropic), utilizado como herramienta de apoyo durante el desarrollo.

## Licencia

El código de este proyecto es de **dominio público** bajo [The Unlicense](LICENSE): puedes copiarlo, modificarlo, distribuirlo y usarlo para cualquier fin, comercial o no, sin pedir permiso.

Las bibliotecas de terceros que usa la app conservan sus propias licencias (por ejemplo, PdfiumAndroid y PDFium bajo licencias de código abierto, y ML Kit bajo los términos de servicio de Google).
