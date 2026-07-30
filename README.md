# centinela-lab

Banco de pruebas de [Centinela](https://github.com/Centinela-App/Centinela). Un botón por cada
causal de alerta que Centinela debe reconocer, más un control negativo y un generador de carga.

> La guía técnica completa del sistema (arquitectura, despliegue desde cero, CI/CD, pruebas)
> vive en el repositorio de Centinela: [`GUIA_TECNICA.md`](https://github.com/Centinela-App/Centinela/blob/develop/GUIA_TECNICA.md).

## Qué es y qué no es

**Es** un cliente externo. Fabrica transacciones con una forma concreta y las entrega por la API
pública de Centinela, igual que lo haría un banco originador. Después consulta el resultado y lo
muestra.

**No es** parte de Centinela. No comparte código, ni base de datos, ni despliegue, ni repositorio.
Si esta aplicación desaparece, Centinela no se entera. Esa separación es deliberada: una
herramienta de prueba que comparte código con lo que prueba puede pasar por alto exactamente el
error que ambos cometen.

**No decide nada.** La detección, la puntuación, la apertura del caso y la explicación son
enteramente de Centinela. Aquí solo se genera el hecho y se observa qué hizo con él.

## Los escenarios

| Botón | Qué envía | Qué debería pasar |
|---|---|---|
| Transacción normal | Consumo corriente sobre una cuenta con historial estable | **Sin caso.** Control negativo |
| Velocidad anómala | Cuatro transacciones en segundos sobre historial espaciado | Regla `velocity` |
| Monto atípico | Consumo ~84× el promedio de la cuenta | Regla `atypical-amount` |
| Geografía imposible | Medellín y, once minutos después, Madrid | Regla `geo-impossible` |
| Comercio de riesgo | Consumo en categoría marcada como riesgosa | Regla `risky-merchant` |
| Fraude combinado | Monto desmedido en otro continente | Varias reglas; explicación completa |
| Documento ilegible | PDF corrupto adjunto a un caso abierto | El caso sigue consultable y el analista es notificado |
| Carga sostenida | Ráfaga configurable de transacciones por segundo | El número de instancias sube y luego baja |

### Por qué cada escenario siembra historial primero

Las reglas de Centinela comparan contra el pasado de la cuenta. Un monto de cuatro millones
sobre una cuenta recién creada **no activa nada**: sin promedio previo no hay nada que superar.
Por eso cada escenario envía primero varias transacciones corrientes que construyen la línea
base, y solo después la transacción anómala.

Cada ejecución usa una cuenta nueva. Reutilizarla haría que una demostración contaminara la
siguiente: tras tres ejecuciones del escenario de monto atípico, el promedio de la cuenta ya
incluiría los montos desmedidos y la regla dejaría de activarse.

### El control negativo no es opcional

Un detector que marca absolutamente todo "acierta" en los cinco escenarios fraudulentos. El
botón de transacción normal es lo único que distingue un sistema que funciona de uno que
sospecha de todo.

## Ejecución local

Requiere Java 21, Maven y una sesión de Azure (`az login`) con acceso al *app role* `SERVICE`.

```bash
cp .env.example .env      # completar CENTINELA_BASE_URL y CENTINELA_SCOPE
set -a && source .env && set +a
mvn spring-boot:run
```

Abrir `http://localhost:8081`.

### Credenciales

No hay ninguna en este repositorio. La identidad la resuelve `DefaultAzureCredential`: Managed
Identity cuando la aplicación corre en Azure, y la sesión de Azure CLI o las variables de entorno
del desarrollador en local. Los dos valores de `.env` —una URL y un ámbito— son identificadores
públicos, no secretos.

## Contenedor

```bash
docker build -t centinela-lab:local .
docker run --rm -p 8081:8081 \
  -e CENTINELA_BASE_URL="https://..." \
  -e CENTINELA_SCOPE="api://.../.default" \
  centinela-lab:local
```

La imagen se construye en varias etapas: el JDK y el repositorio de Maven se quedan en la etapa
de compilación. No es solo cuestión de tamaño — las capas conservan todo lo que existió en
ellas, así que borrar algo en una capa posterior no lo elimina de la imagen.

## Despliegue en Azure

El banco de pruebas se despliega **sobre la plataforma de Centinela ya desplegada** (comparte
grupo de recursos, registro y entorno de Container Apps; la justificación está en la cabecera
de `scripts/deploy-lab.sh`). Con los mismos cuatro parámetros usados al desplegar Centinela:

```bash
# .env en la raíz (o exportados): SUBSCRIPTION_ID, RESOURCE_GROUP, LOCATION, NAME_PREFIX
bash scripts/deploy-lab.sh --yes
```

El script crea la identidad propia (`id-<prefijo>-lab`), le concede los app roles `SERVICE` y
`ANALYST` y el rol `AcrPull`, construye la imagen dentro de Azure (`az acr build`), crea o
actualiza la Container App (`ca-<prefijo>-lab`, escala 0→2) y sonda su salud. También lo invoca
`deploy-platform.sh --with-lab` desde el repositorio de Centinela.

### CI/CD

`.github/workflows/ci-cd.yml`: pruebas + escaneo de credenciales + shellcheck en cada push/PR;
en `main`, despliegue vía OIDC (sin credenciales almacenadas) ejecutando
`deploy-lab.sh --skip-role --skip-acrpull` — las dos concesiones son aprovisionamiento que hace
una persona una única vez. Necesita en este repositorio:

- **Secrets**: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` (identificadores
  del app OIDC; los imprime `provision-github-oidc.sh` de Centinela, federado con este repo).
- **Variables**: `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `NAME_PREFIX`,
  `CENTINELA_ENTRA_APP_ID` (appId de la API) y `DESPLIEGUE_HABILITADO=true` para activar el CD.

## Interpretar el resultado

La pantalla contrasta lo observado con lo que el escenario anticipaba. **Un desajuste no es un
fallo de esta herramienta**: significa que Centinela decidió algo distinto de lo previsto. Eso
puede querer decir que una regla está mal calibrada, que la configuración de comercios de riesgo
no incluye el del escenario, o que la expectativa era ingenua. Las tres son información útil.

El identificador de traza que aparece en el resultado es el mismo que hay que buscar en
Application Insights para ver el recorrido completo de la transacción, etapa por etapa.

## Escenarios de fallo

Dos botones existen precisamente para provocar problemas:

- **Documento ilegible.** Abre un caso legítimo y le adjunta un archivo corrupto. Lo que se
  comprueba no es que la extracción funcione —se sabe que no puede— sino que el caso sigue
  consultable y que el resultado del intento queda registrado para el analista.
- **Explicador detenido.** No tiene botón propio: se provoca escalando a cero la Container App
  del explicador en Centinela y lanzando después cualquier escenario fraudulento. El caso debe
  abrirse igual y mostrarse como `PENDING`. Al restablecer el explicador, la explicación aparece
  sola.

## Demostrar el escalado

1. En este repositorio: pulsar **Generar carga** con 20 tx/s durante 120 s.
2. En el repositorio de Centinela, en paralelo: `bash scripts/verify/verify-scaling.sh`.

El segundo muestra el número de réplicas en vivo y guarda la evidencia. Un `429` durante la
carga no es un error: es el limitador de tasa de Centinela haciendo su trabajo, y se contabiliza
aparte precisamente para poder distinguirlo.

## Límites conocidos

- Los escenarios son **sincrónicos**: cada uno tarda entre diez y cuarenta segundos porque
  espera al motor y al explicador. Durante una sustentación es preferible a tener que refrescar.
- La carga se limita a 50 tx/s y 180 segundos (el ingreso de Container Apps corta las
  peticiones sincrónicas a los 240 s). El proyecto tiene un tope de crédito de 60 USD y la
  generación de carga es, junto con la telemetría, lo que más lo consume.
- Esta aplicación replica los contratos de Centinela en lugar de depender de un artefacto común.
  Si Centinela cambia el contrato, aquí se descubre al fallar con un `400` — la API rechaza toda
  propiedad no declarada, así que la divergencia se manifiesta de inmediato y no en silencio.
