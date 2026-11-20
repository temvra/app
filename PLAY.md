# Temvra en Google Play

Cuenta de desarrollador: **Kelestra** (cuenta personal; antes se llamaba Karteria). La propiedad pasa de karteria.studio@gmail.com a **kelestra.studio@gmail.com**: transferencia enviada el 19 de noviembre de 2026, con 7 días de espera; kelestra.studio ya es administradora. App: **Temvra**, paquete `com.temvra.app` (creada en Play Console el 20 de noviembre de 2026).

## 1. Generar el paquete
1. En GitHub: **Actions › Compilar para Google Play › Run workflow**.
2. Cuando termine, abre esa ejecución y descarga el artefacto **Temvra-GooglePlay-1.0.N** (un .zip con el .aab dentro).
3. Cada subida a Play necesita un número mayor: basta con lanzar el flujo otra vez.
4. Con la casilla «Publicar también el APK en temvra/descargas» (viene marcada), el flujo publica además el APK de esa misma versión en [temvra/descargas](https://github.com/temvra/descargas), público y sin código. Enlace directo a la última: `https://github.com/temvra/descargas/releases/latest/download/Temvra.apk`. Usa el secreto `DESCARGAS_TOKEN` (token de grano fino con Contents en Read and write solo para temvra/descargas).

El paquete va firmado con la clave de subida (la misma del keystore). La primera vez, Play Console ofrece **la firma de apps de Google Play**: acéptala.

## 2. Crear la app en Play Console
- **Crear app** › Nombre: Temvra · Paquete: `com.temvra.app` · App · Gratis.
- Idioma predeterminado: es el de reserva para quien tenga el móvil en un idioma sin traducir. Añade la ficha traducida a los 10 idiomas de la app (y español de Latinoamérica, `es-419`): Google Play enseña a cada usuario la de su idioma.
- Categoría: **Productividad**. Correo de contacto: **hola@kelestra.com** (reenvío de Cloudflare a kelestra.studio@gmail.com). Web: https://temvra.github.io hasta que https://temvra.es tenga la web.

## 3. Ficha de Play Store
Todo está en `fastlane/metadata/android/es-ES/`:
- Nombre: `title.txt` · Descripción breve: `short_description.txt` · Descripción completa: `full_description.txt`
- Icono 512×512: `images/icon.png` · Gráfico destacado 1024×500: `images/featureGraphic.png`
- Capturas del teléfono (1080×1920): `images/phoneScreenshots/1.png` a `6.png`

## 4. Contenido de la app (formularios de Play Console)
| Apartado | Respuesta |
|---|---|
| Política de privacidad | https://temvra.github.io/privacidad.html (cuando temvra.es tenga la web, https://temvra.es/privacidad.html). La app la enlaza en Ajustes › Ayuda › Política de privacidad, como pide Google |
| Anuncios | No contiene anuncios |
| Acceso a la app | Todas las funciones están disponibles sin acceso especial |
| Clasificación de contenido | Categoría «Utilidades, productividad, comunicación u otros»; responde «No» a violencia, sexo, lenguaje, drogas, apuestas y compras. Saldrá PEGI 3 |
| Público objetivo | 13 años o más (si eliges menores de 13 se aplican las normas de apps para familias) |
| Apps de noticias / sanidad / gobierno / financieras | No |
| Estado de operador (UE) | No operador mientras la app sea gratis y sin anuncios ni compras |

### Seguridad de los datos
La app no tiene servidores: todo se queda en el dispositivo. Lo único que sale del dispositivo es la **sincronización opcional con el repositorio de GitHub del propio usuario**. Respuesta prudente:
- ¿Recoge o comparte datos? **Sí** (por la sincronización con GitHub).
- Tipos: **Archivos y documentos** y **Calendario** (eventos). Recogidos, no compartidos.
- Finalidad: **Funciones de la app**. Es **opcional**: el usuario decide si la activa.
- ¿Cifrados en tránsito? **Sí** (HTTPS).
- ¿Se pueden borrar? **Sí**: desde la app o borrando el repositorio en GitHub.
- No hay cuentas, así que no hace falta enlace para eliminar la cuenta.

### Declaraciones de permisos
- **Alarmas exactas (USE_EXACT_ALARM):** función principal de alarmas y recordatorios de calendario. Marca «Despertador» y «Calendario».
- **Notificaciones a pantalla completa (USE_FULL_SCREEN_INTENT):** para mostrar la alarma que está sonando con el móvil bloqueado.
- **Servicio en primer plano «reproducción multimedia»:** «Reproduce el sonido de las alarmas y los avisos sonoros y por voz del temporizador y de los circuitos mientras la app está en segundo plano. Lo inicia el usuario y lo puede parar desde la notificación». Piden un **vídeo corto** (enlace de YouTube oculto o de Drive) en el que se vea: iniciar un temporizador, salir de la app, la notificación con la cuenta atrás y el aviso sonando.

## 5. Prueba cerrada (obligatoria en cuentas personales nuevas)
1. **Pruebas › Prueba cerrada › Crear canal**. Sube el .aab.
2. Añade una lista de correo con **al menos 12 testers** (cuentas de Gmail).
3. Comparte el enlace de participación. Tienen que instalarla desde Play y mantenerla **14 días seguidos**.
4. Después, en el Panel: **Solicitar acceso a producción** y responde el formulario sobre la prueba.
