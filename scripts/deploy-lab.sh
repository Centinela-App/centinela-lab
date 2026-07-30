#!/usr/bin/env bash
#
# scripts/deploy-lab.sh
# Despliega el banco de pruebas en la plataforma de Centinela, con un solo comando.
#
# POR QUE ESTE SCRIPT NO EXISTIA Y HACIA FALTA
# -------------------------------------------
# El banco de pruebas solo se desplegaba desde su propio pipeline de GitHub, y ese
# pipeline daba por hechas tres cosas que nadie creaba:
#
#   1. UNA IDENTIDAD PROPIA. Usaba 'id-<prefijo>-acrpull', la identidad cuyo unico
#      proposito es descargar imagenes del registro. Funciona para arrancar el
#      contenedor y no para nada mas: no es la identidad del banco de pruebas, es
#      la del registro, y darle permisos de cliente de la API mezcla dos papeles
#      que deben poder revocarse por separado.
#   2. EL APP ROLE 'SERVICE'. Definir un app role no lo concede. Sin la asignacion
#      el token llega sin claim 'roles' y Centinela responde 403 a cada envio: un
#      fallo que ocurre DESPUES de un login correcto, que es el sintoma que peor
#      apunta a su causa.
#   3. AZURE_CLIENT_ID. Con identidades asignadas por el usuario,
#      DefaultAzureCredential no puede adivinar cual usar y falla al pedir el
#      token. Con una sola identidad asignada el sintoma es intermitente, que es
#      peor que un fallo consistente.
#
# Aqui se crean y verifican las tres.
#
# POR QUE COMPARTE GRUPO DE RECURSOS CON CENTINELA
# ------------------------------------------------
# El aislamiento que importa —codigo, repositorio, identidad, permisos, pipeline—
# se mantiene intacto: son dos repositorios, dos imagenes, dos identidades y dos
# conjuntos de roles, y el banco de pruebas solo alcanza a Centinela por su API
# publica con un token de Entra, igual que un banco originador real.
#
# Lo que se comparte es la INFRAESTRUCTURA DE SOPORTE: el registro de contenedores,
# el entorno de Container Apps y el workspace de Log Analytics. Duplicarlos tendria
# tres costos y ningun beneficio: un segundo ACR factura aparte, un segundo entorno
# de Container Apps exige otra subred /23 y otro plano de red que mantener, y —el
# argumento que de verdad decide— un segundo workspace ROMPE la traza de extremo a
# extremo. El banco de pruebas envia 'traceparent' precisamente para que el
# recorrido registrado empiece en el originador; con la telemetria en dos
# workspaces distintos esa correlacion hay que reconstruirla a mano y deja de ser
# demostrable. Ademas el ciclo de vida es el mismo: el banco de pruebas existe para
# probar ESTA plataforma y no tiene ninguna razon para sobrevivirla, asi que
# destruir el grupo debe llevarselo tambien. En grupos separados quedaria huerfano.
#
# SIN DOCKER LOCAL
# ----------------
# La imagen se construye con `az acr build`, dentro de Azure. Solo hace falta la
# CLI de Azure.
#
# Uso:
#   bash scripts/deploy-lab.sh [opciones]
#
#     --validate-only  Comprueba requisitos y sale. No crea nada.
#     --yes            Sin confirmacion interactiva.
#     --tag <etiqueta> Etiqueta de imagen (por defecto: SHA corto de HEAD).
#     --skip-image     No construye; despliega la etiqueta indicada.
#     --skip-role      No toca el app role (ya concedido). Ver mas abajo.
#     --skip-acrpull   No toca la asignacion AcrPull (ya concedida). Ver mas abajo.
#     --local-docker   Construye con el Docker local.
#
# Parametros (de .env, del entorno, o heredados del despliegue de Centinela):
#   SUBSCRIPTION_ID  RESOURCE_GROUP  LOCATION  NAME_PREFIX
#
# Deben ser LOS MISMOS que se usaron para desplegar Centinela: de ellos se derivan
# el nombre del registro, del entorno de Container Apps y del registro de Entra.
#
# EJECUCION DESDE UN PIPELINE: --skip-role
# ---------------------------------------
# Conceder un app role exige permiso de ESCRITURA en el directorio
# (AppRoleAssignment.ReadWrite.All). Darselo al service principal de un pipeline
# significa que cualquiera capaz de modificar el workflow puede concederse roles
# de aplicacion: un privilegio muy superior al de desplegar un contenedor, y
# permanente. Por eso la concesion es un paso de aprovisionamiento que hace una
# persona UNA VEZ, y el despliegue continuo corre con --skip-role.
#
# Con --skip-role tampoco se consulta Microsoft Graph, asi que el pipeline no
# necesita ni permiso de lectura del directorio. A cambio hay que darle el appId
# como variable del repositorio:
#
#   CENTINELA_ENTRA_APP_ID   appId del registro de la API (identificador, no secreto)
#   CENTINELA_API_FQDN       opcional; si falta, se resuelve desde Azure
#
# El script AVISA si con --skip-role no encuentra la asignacion, en vez de
# desplegar en silencio algo que responderia 403 en la primera peticion.
#
# EJECUCION DESDE UN PIPELINE: --skip-acrpull
# -------------------------------------------
# Mismo razonamiento que --skip-role, pero en el plano de RBAC de Azure: escribir
# una asignacion de rol exige Microsoft.Authorization/roleAssignments/write, que
# Contributor NO incluye. El service principal del pipeline tiene AcrPush y
# Contributor, asi que conceder AcrPull desde CI falla con AuthorizationFailed.
# La concesion es un paso de aprovisionamiento que hace una persona UNA VEZ
# (ejecutando este script sin --skip-acrpull), y el despliegue continuo corre con
# --skip-acrpull.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VALIDATE_ONLY=0
ASSUME_YES=0
SKIP_IMAGE=0
SKIP_ROLE=0
SKIP_ACRPULL=0
LOCAL_DOCKER=0
IMAGE_TAG=""

usage() { sed -n '3,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --validate-only) VALIDATE_ONLY=1; shift ;;
    --yes|--force)   ASSUME_YES=1; shift ;;
    --skip-image)    SKIP_IMAGE=1; shift ;;
    --skip-role)     SKIP_ROLE=1; shift ;;
    --skip-acrpull)  SKIP_ACRPULL=1; shift ;;
    --local-docker)  LOCAL_DOCKER=1; shift ;;
    --tag)           IMAGE_TAG="${2:?--tag requiere valor}"; shift 2 ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "Argumento desconocido: $1. Usa --help." >&2; exit 1 ;;
  esac
done

# --- Utilidades -----------------------------------------------------------------
#
# Este repositorio es independiente y no puede depender de que el de Centinela
# este clonado al lado, asi que trae su propio logging minimo en vez de hacer
# 'source' de scripts/lib/common.sh. Es duplicacion consciente: la alternativa es
# un acoplamiento entre repositorios que rompe el despliegue de uno cuando el otro
# se mueve de sitio.

if [ -t 2 ]; then
  _C_RED=$'\033[31m'; _C_YELLOW=$'\033[33m'; _C_GREEN=$'\033[32m'; _C_RESET=$'\033[0m'
else
  _C_RED=''; _C_YELLOW=''; _C_GREEN=''; _C_RESET=''
fi
log_info()  { printf '%s[INFO]%s  %s\n' "$_C_GREEN"  "$_C_RESET" "$*" >&2; }
log_warn()  { printf '%s[WARN]%s  %s\n' "$_C_YELLOW" "$_C_RESET" "$*" >&2; }
log_error() { printf '%s[ERROR]%s %s\n' "$_C_RED"    "$_C_RESET" "$*" >&2; }
die() { log_error "$*"; exit 1; }
mask() { local v="${1:-}"; [ "${#v}" -le 8 ] && printf '****' || printf '%s…%s' "${v:0:4}" "${v: -4}"; }

# En Windows la CLI de Azure imprime CRLF y todo "$(az ... -o tsv)" arrastra un \r
# final: las comparaciones de identificadores fallan siempre y el error no dice por
# que. Mismo tratamiento que en el repositorio de Centinela.
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    export MSYS2_ARG_CONV_EXCL='/subscriptions/;/providers/'
    az() { command az "$@" | tr -d '\r'; }
    ;;
esac

with_retry() {
  local max="$1"; shift; local attempt=1
  until "$@"; do
    [ "$attempt" -ge "$max" ] && return 1
    log_warn "Intento $attempt/$max fallo; reintentando en ${attempt}s..."
    sleep "$attempt"; attempt=$((attempt + 1))
  done
}

# --- Parametros -----------------------------------------------------------------

cargar_parametros() {
  local env_file="${ENV_FILE:-$REPO_ROOT/.env}"
  if [ -f "$env_file" ]; then
    log_info "Cargando parametros desde $env_file"
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi

  local faltan=() p
  for p in SUBSCRIPTION_ID RESOURCE_GROUP LOCATION NAME_PREFIX; do
    [ -z "${!p:-}" ] && faltan+=("$p")
  done
  if [ "${#faltan[@]}" -gt 0 ]; then
    log_error "Faltan parametros: ${faltan[*]}"
    log_error "Deben coincidir con los usados al desplegar Centinela. Definelos en un"
    log_error "archivo .env en la raiz del repositorio o exportalos, por ejemplo:"
    log_error "  SUBSCRIPTION_ID=<uuid>  RESOURCE_GROUP=rg-centinela  LOCATION=eastus2  NAME_PREFIX=cent"
    exit 1
  fi

  [[ "$NAME_PREFIX" =~ ^[a-z0-9]{3,11}$ ]] \
    || die "NAME_PREFIX debe ser 3-11 minusculas/numeros (actual: '$NAME_PREFIX')."
}

# --- Nombres derivados ----------------------------------------------------------

APP_NAME=""
IDENTITY_NAME=""
REGISTRY_NAME=""
ENVIRONMENT_NAME=""
ACRPULL_IDENTITY=""
ENTRA_APP_NAME=""

derivar_nombres() {
  APP_NAME="ca-${NAME_PREFIX}-lab"
  IDENTITY_NAME="id-${NAME_PREFIX}-lab"
  ACRPULL_IDENTITY="id-${NAME_PREFIX}-acrpull"
  REGISTRY_NAME="${NAME_PREFIX}acr"
  ENVIRONMENT_NAME="cae-${NAME_PREFIX}"
  ENTRA_APP_NAME="${CENTINELA_ENTRA_APP_NAME:-${NAME_PREFIX}-api-week1}"
  [ -n "$IMAGE_TAG" ] || IMAGE_TAG="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo latest)"
}

# Los app roles que sostiene la identidad del banco de pruebas. La justificacion
# completa esta junto a conceder_app_roles(); se declara aqui porque preflight()
# ya lo usa al imprimir el plan.
readonly APP_ROLES_DEL_LAB=(SERVICE ANALYST)

# --- Preflight ------------------------------------------------------------------

API_APP_ID=""
API_SP_ID=""
API_FQDN=""

preflight() {
  command -v az >/dev/null 2>&1 || die "Falta la CLI de Azure ('az')."
  command -v git >/dev/null 2>&1 || log_warn "Sin 'git': la etiqueta por defecto sera 'latest'."
  if [ "$LOCAL_DOCKER" -eq 1 ]; then
    command -v docker >/dev/null 2>&1 || die "--local-docker pero falta 'docker'."
    docker info >/dev/null 2>&1 || die "El demonio de Docker no responde."
  fi

  az account show >/dev/null 2>&1 || die "No hay sesion de Azure activa. Ejecuta 'az login'."
  local activa; activa="$(az account show --query id -o tsv)"
  [ "$activa" = "$SUBSCRIPTION_ID" ] \
    || die "Suscripcion activa ($(mask "$activa")) != SUBSCRIPTION_ID ($(mask "$SUBSCRIPTION_ID"))."

  # La plataforma de Centinela tiene que existir ANTES. Se comprueba cada pieza por
  # separado para poder decir cual falta: "la plataforma no esta lista" obliga a
  # buscar a mano lo que este mensaje ya sabe.
  az group show -n "$RESOURCE_GROUP" >/dev/null 2>&1 \
    || die "No existe el grupo '$RESOURCE_GROUP'. Despliega Centinela primero (scripts/deploy-platform.sh)."
  az acr show -n "$REGISTRY_NAME" -g "$RESOURCE_GROUP" >/dev/null 2>&1 \
    || die "No existe el registro '$REGISTRY_NAME'. Falta provision-container-registry.sh en Centinela."
  az containerapp env show -g "$RESOURCE_GROUP" -n "$ENVIRONMENT_NAME" >/dev/null 2>&1 \
    || die "No existe el entorno '$ENVIRONMENT_NAME'. Falta provision-container-apps.sh en Centinela."
  az identity show -n "$ACRPULL_IDENTITY" -g "$RESOURCE_GROUP" >/dev/null 2>&1 \
    || die "No existe la identidad de pull '$ACRPULL_IDENTITY'."

  # CENTINELA_ENTRA_APP_ID permite fijar el appId sin consultar el directorio. Es
  # lo que hace posible que el pipeline funcione sin permisos de Graph: el appId es
  # un identificador publico, no un secreto, y darle al pipeline lectura del
  # directorio entero para obtener un dato que ya conoce es un intercambio malo.
  API_APP_ID="${CENTINELA_ENTRA_APP_ID:-}"
  if [ -z "$API_APP_ID" ]; then
    API_APP_ID="$(az ad app list --display-name "$ENTRA_APP_NAME" --query '[0].appId' -o tsv 2>/dev/null || true)"
  fi
  if [ -z "$API_APP_ID" ] || [ "$API_APP_ID" = "None" ]; then
    log_error "No se pudo determinar el appId del registro '$ENTRA_APP_NAME'."
    log_error "Si Centinela ya esta desplegado, la causa habitual es que la identidad que"
    log_error "ejecuta no tiene permiso de lectura del directorio (tipico en un pipeline)."
    die "Define CENTINELA_ENTRA_APP_ID con el appId, o ejecuta provision-entra-app.sh en Centinela."
  fi

  # El service principal solo hace falta para escribir la asignacion del app role.
  # Con --skip-role no se consulta, y asi el pipeline no necesita Graph en absoluto.
  if [ "$SKIP_ROLE" -eq 0 ]; then
    API_SP_ID="$(az ad sp list --filter "appId eq '$API_APP_ID'" --query '[0].id' -o tsv 2>/dev/null || true)"
    [ -n "$API_SP_ID" ] && [ "$API_SP_ID" != "None" ] \
      || die "El registro '$ENTRA_APP_NAME' no tiene service principal. Ejecuta provision-entra-app.sh."
  fi

  # La direccion de la API se resuelve, no se pide: escribirla a mano es la fuente
  # habitual de un banco de pruebas que apunta a un sitio que no existe.
  API_FQDN="${CENTINELA_API_FQDN:-$(az containerapp show -g "$RESOURCE_GROUP" -n "ca-${NAME_PREFIX}-api" \
    --query properties.configuration.ingress.fqdn -o tsv 2>/dev/null || true)}"
  [ -n "$API_FQDN" ] \
    || die "La API 'ca-${NAME_PREFIX}-api' no esta desplegada o no tiene ingreso. Despliega Centinela primero."

  log_info "Plan:"
  log_info "  Grupo de recursos : $RESOURCE_GROUP  (compartido con Centinela)"
  log_info "  Aplicacion        : $APP_NAME"
  log_info "  Identidad propia  : $IDENTITY_NAME"
  log_info "  Entorno ACA       : $ENVIRONMENT_NAME  (compartido)"
  log_info "  Registro          : ${REGISTRY_NAME}.azurecr.io  (compartido)"
  log_info "  Etiqueta          : $IMAGE_TAG"
  log_info "  API objetivo      : https://$API_FQDN"
  log_info "  Ambito del token  : api://${API_APP_ID}/.default"
  log_info "  App roles         : ${APP_ROLES_DEL_LAB[*]}$([ "$SKIP_ROLE" -eq 1 ] && echo ' (--skip-role: no se tocan)')"
}

confirmar() {
  [ "$ASSUME_YES" -eq 1 ] && return 0
  [ -t 0 ] || die "Sesion no interactiva: vuelve a ejecutar con --yes."
  printf 'Se creara/actualizara el banco de pruebas en %s. Escribe "si" para continuar: ' "$RESOURCE_GROUP" >&2
  local r; read -r r
  case "$r" in si|SI|Si|s|S|yes|y) return 0 ;; *) die "Abortado por el operador." ;; esac
}

# --- Identidad propia -----------------------------------------------------------

LAB_PRINCIPAL_ID=""
LAB_CLIENT_ID=""
LAB_IDENTITY_ID=""

asegurar_identidad() {
  if ! az identity show -n "$IDENTITY_NAME" -g "$RESOURCE_GROUP" >/dev/null 2>&1; then
    log_info "Creando la identidad '$IDENTITY_NAME'..."
    az identity create -n "$IDENTITY_NAME" -g "$RESOURCE_GROUP" -l "$LOCATION" --output none
    # El principal tarda en propagarse al directorio. Sin esta espera, la
    # asignacion del app role falla de forma intermitente.
    sleep 15
  else
    log_info "La identidad '$IDENTITY_NAME' ya existe."
  fi

  LAB_PRINCIPAL_ID="$(az identity show -n "$IDENTITY_NAME" -g "$RESOURCE_GROUP" --query principalId -o tsv)"
  LAB_CLIENT_ID="$(az identity show -n "$IDENTITY_NAME" -g "$RESOURCE_GROUP" --query clientId -o tsv)"
  LAB_IDENTITY_ID="$(az identity show -n "$IDENTITY_NAME" -g "$RESOURCE_GROUP" --query id -o tsv)"
  [ -n "$LAB_PRINCIPAL_ID" ] || die "La identidad no tiene principalId."
  log_info "  principal: $(mask "$LAB_PRINCIPAL_ID")  client: $(mask "$LAB_CLIENT_ID")"
}

# --- App role SERVICE -----------------------------------------------------------
#
# Un app role NO es un rol de RBAC de Azure. 'az role assignment' concede permisos
# sobre RECURSOS; esto concede permiso DENTRO de la aplicacion, y se escribe en
# /servicePrincipals/{id}/appRoleAssignedTo de Microsoft Graph. Ningun rol de RBAC
# sobre el grupo hace que un token lleve roles=["SERVICE"].
# El banco de pruebas necesita DOS roles porque en sus escenarios representa a dos
# actores distintos:
#
#   SERVICE  -> enviar transacciones, como un banco originador.
#   ANALYST  -> adjuntar el documento corrupto del escenario de documento ilegible,
#               que es una accion de analista.
#
# Sostener los dos es correcto para un banco de pruebas y seria un problema en un
# cliente real. Lo que NO se hizo fue ampliar los permisos de SERVICE para cubrir la
# carga de documentos: eso habria borrado la frontera que la matriz de permisos
# define, en vez de concederle al banco de pruebas los dos papeles que de verdad
# ejerce. El banco de pruebas no tiene ningun acceso a datos: solo habla por la API
# publica, igual que cualquier cliente externo.
# (La declaracion de APP_ROLES_DEL_LAB esta arriba, antes de preflight.)

conceder_app_roles() {
  local rol
  for rol in "${APP_ROLES_DEL_LAB[@]}"; do
    conceder_un_app_role "$rol"
  done
}

conceder_un_app_role() {
  local rol="$1" role_id existente tipos
  role_id="$(az ad app show --id "$API_APP_ID" \
    --query "appRoles[?value=='$rol' && isEnabled].id | [0]" -o tsv 2>/dev/null || true)"
  [ -n "$role_id" ] && [ "$role_id" != "None" ] \
    || die "El app role '$rol' no existe en '$ENTRA_APP_NAME'. Reejecuta provision-entra-app.sh."

  # Un rol de tipo solo-User concedido a una Managed Identity falla en Graph con un
  # mensaje que no menciona allowedMemberTypes. Se comprueba antes para poder
  # explicar la causa real y no dejar al operador buscando un problema de permisos.
  tipos="$(az ad app show --id "$API_APP_ID" \
    --query "appRoles[?value=='$rol'].allowedMemberTypes | [0]" -o json 2>/dev/null || echo '[]')"
  if ! printf '%s' "$tipos" | grep -q 'Application'; then
    log_error "El app role '$rol' solo admite miembros de tipo User ($tipos)."
    log_error "Una Managed Identity no puede sostenerlo."
    die "Actualiza el registro en Centinela: bash scripts/provision-entra-app.sh"
  fi

  existente="$(az rest --method get \
    --url "https://graph.microsoft.com/v1.0/servicePrincipals/${API_SP_ID}/appRoleAssignedTo" \
    --query "value[?principalId=='${LAB_PRINCIPAL_ID}' && appRoleId=='${role_id}'].id | [0]" \
    -o tsv 2>/dev/null | grep -v '^None$' || true)"

  if [ -n "$existente" ]; then
    log_info "El app role '$rol' ya estaba concedido a la identidad del banco de pruebas."
    return 0
  fi

  log_info "Concediendo el app role '$rol' a '$IDENTITY_NAME'..."
  local body salida codigo intento
  body="$(printf '{"principalId":"%s","resourceId":"%s","appRoleId":"%s"}' \
    "$LAB_PRINCIPAL_ID" "$API_SP_ID" "$role_id")"

  for intento in 1 2 3; do
    set +e
    salida="$(az rest --method post \
      --url "https://graph.microsoft.com/v1.0/servicePrincipals/${API_SP_ID}/appRoleAssignedTo" \
      --headers 'Content-Type=application/json' --body "$body" --output none 2>&1)"
    codigo=$?
    set -e
    [ "$codigo" -eq 0 ] && break
    if printf '%s' "$salida" | grep -qi 'already exists'; then codigo=0; break; fi
    if printf '%s' "$salida" | grep -qi 'does not exist\|not found\|ResourceNotFound'; then
      log_warn "  el principal aun no propago en el directorio (intento $intento/3)..."
      sleep 20
      continue
    fi
    break
  done

  if [ "$codigo" -ne 0 ]; then
    log_error "No se pudo conceder el app role. Error real de Graph:"
    printf '%s\n' "$salida" | head -5 >&2
    log_error "Causa habitual: la cuenta que ejecuta no tiene rol de directorio para escribir"
    log_error "asignaciones de app role (necesita Application Administrator, Cloud Application"
    log_error "Administrator, o ser propietario de la aplicacion '$ENTRA_APP_NAME')."
    die "Sin este rol el banco de pruebas se autentica y Centinela le responde 403."
  fi

  # No se confia en el codigo de salida: se vuelve a consultar el directorio.
  existente="$(az rest --method get \
    --url "https://graph.microsoft.com/v1.0/servicePrincipals/${API_SP_ID}/appRoleAssignedTo" \
    --query "value[?principalId=='${LAB_PRINCIPAL_ID}' && appRoleId=='${role_id}'].id | [0]" \
    -o tsv 2>/dev/null | grep -v '^None$' || true)"
  [ -n "$existente" ] || die "La asignacion no aparece en el directorio tras crearla."
  log_info "  concedido y verificado."
}

# Con --skip-role se intenta CONFIRMAR que el rol ya esta concedido. Si la consulta
# no es posible —el caso normal en un pipeline sin permisos de Graph— se avisa y se
# sigue: no poder comprobarlo no es lo mismo que saber que falta, y bloquear el
# despliegue por una comprobacion imposible seria bloquearlo siempre. Lo que no se
# hace es callar: sin ese rol el banco de pruebas recibe 403 en la primera peticion
# y el mensaje de arriba es lo unico que apunta a la causa.
advertir_si_falta_el_rol() {
  if [ -z "$API_SP_ID" ]; then
    API_SP_ID="$(az ad sp list --filter "appId eq '$API_APP_ID'" --query '[0].id' -o tsv 2>/dev/null || true)"
  fi
  if [ -z "$API_SP_ID" ] || [ "$API_SP_ID" = "None" ]; then
    log_warn "  no se pudo consultar el directorio para confirmar los app roles."
    log_warn "  Si el banco de pruebas responde 403, concedelos con una cuenta con permisos:"
    log_warn "    bash scripts/deploy-lab.sh --skip-image --yes"
    return 0
  fi

  local asignaciones rol role_id faltan=()
  asignaciones="$(az rest --method get \
    --url "https://graph.microsoft.com/v1.0/servicePrincipals/${API_SP_ID}/appRoleAssignedTo" \
    --query "value[?principalId=='${LAB_PRINCIPAL_ID}'].appRoleId" -o tsv 2>/dev/null || true)"

  for rol in "${APP_ROLES_DEL_LAB[@]}"; do
    role_id="$(az ad app show --id "$API_APP_ID" \
      --query "appRoles[?value=='$rol' && isEnabled].id | [0]" -o tsv 2>/dev/null || true)"
    [ -n "$role_id" ] && [ "$role_id" != "None" ] || continue
    printf '%s\n' "$asignaciones" | grep -qF "$role_id" || faltan+=("$rol")
  done

  if [ "${#faltan[@]}" -eq 0 ]; then
    log_info "  confirmado: los app roles (${APP_ROLES_DEL_LAB[*]}) estan concedidos."
  else
    log_warn "  ATENCION: faltan los app roles: ${faltan[*]}"
    log_warn "  El banco de pruebas se desplegara y Centinela le respondera 403 en los"
    log_warn "  escenarios que dependan de ellos. Concedelos con una cuenta con permisos:"
    log_warn "    bash scripts/deploy-lab.sh --skip-image --yes"
  fi
}

# --- Permiso de descarga de imagen ---------------------------------------------
#
# La identidad propia necesita AcrPull para que Container Apps pueda arrancar el
# contenedor con ella. Se le concede a la identidad del banco de pruebas en vez de
# reutilizar la del registro: asi el banco de pruebas usa UNA identidad para todo
# lo suyo y se revoca de una sola vez.
conceder_acrpull() {
  local acr_id role_id sub_id assignment_id body salida codigo
  acr_id="$(az acr show -n "$REGISTRY_NAME" -g "$RESOURCE_GROUP" --query id -o tsv)"
  sub_id="$(az account show --query id -o tsv)"
  role_id="$(az role definition list --name AcrPull --query '[0].name' -o tsv)"
  [ -n "$role_id" ] || die "No se encontro la definicion del rol AcrPull."

  # Se usa la API REST y no 'az role assignment create': en algunas suscripciones
  # —una cuenta Microsoft personal sobre un directorio predeterminado— TODAS las
  # operaciones de 'az role assignment' fallan con "(MissingSubscription) The
  # request did not have a subscription or a valid tenant level resource
  # provider", que no es un problema de suscripcion ni de permisos sino un defecto
  # del comando. Contra REST funciona sin cambios. Mismo camino que usa Centinela.
  assignment_id="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null \
    || python -c 'import uuid; print(uuid.uuid4())' 2>/dev/null \
    || cat /proc/sys/kernel/random/uuid 2>/dev/null)"
  [ -n "$assignment_id" ] || die "No se pudo generar un identificador de asignacion."

  body="$(printf '{"properties":{"roleDefinitionId":"/subscriptions/%s/providers/Microsoft.Authorization/roleDefinitions/%s","principalId":"%s","principalType":"ServicePrincipal"}}' \
    "$sub_id" "$role_id" "$LAB_PRINCIPAL_ID")"

  set +e
  salida="$(az rest --method put \
    --url "https://management.azure.com${acr_id}/providers/Microsoft.Authorization/roleAssignments/${assignment_id}?api-version=2022-04-01" \
    --body "$body" --output none 2>&1)"
  codigo=$?
  set -e

  if [ "$codigo" -eq 0 ]; then
    log_info "Rol 'AcrPull' concedido sobre el registro."
    # La asignacion RBAC tarda en propagar al plano de datos del registro. Sin
    # esta espera, el primer 'az containerapp create --registry-identity' puede
    # fallar el pull de la imagen de forma intermitente.
    log_info "Esperando 30s la propagacion de RBAC..."
    sleep 30
  elif printf '%s' "$salida" | grep -qi 'RoleAssignmentExists\|already exists'; then
    log_info "El rol 'AcrPull' ya estaba concedido."
  else
    log_error "No se pudo conceder AcrPull. Error real de Azure:"
    printf '%s\n' "$salida" | head -4 >&2
    die "Sin AcrPull, Container Apps no puede descargar la imagen y la revision no arranca."
  fi
}

# --- Imagen ---------------------------------------------------------------------

construir_imagen() {
  local registry="${REGISTRY_NAME}.azurecr.io"
  if [ "$LOCAL_DOCKER" -eq 1 ]; then
    log_info "Construyendo con el Docker local..."
    az acr login --name "$REGISTRY_NAME"
    docker build -t "${registry}/centinela-lab:${IMAGE_TAG}" "$REPO_ROOT"
    docker push "${registry}/centinela-lab:${IMAGE_TAG}"
  else
    log_info "Construyendo en Azure con 'az acr build' (sin Docker local)..."
    az acr build --registry "$REGISTRY_NAME" \
      --image "centinela-lab:${IMAGE_TAG}" \
      --image "centinela-lab:latest" \
      --file Dockerfile "$REPO_ROOT"
  fi
}

# --- Container App --------------------------------------------------------------

desplegar_app() {
  local registry="${REGISTRY_NAME}.azurecr.io"
  local imagen="${registry}/centinela-lab:${IMAGE_TAG}"
  local base_url="https://${API_FQDN}"
  # El ambito se construye con el appId, no con un valor escrito a mano: es el
  # dato que mas se copia mal y el sintoma es un 401 sin explicacion.
  local scope="api://${API_APP_ID}/.default"

  if az containerapp show -g "$RESOURCE_GROUP" -n "$APP_NAME" >/dev/null 2>&1; then
    log_info "Actualizando '$APP_NAME' a $imagen"
    # La identidad y el registro se reafirman antes del update: 'az containerapp
    # update' no acepta --user-assigned ni --registry-identity, y si la app la
    # creo una version anterior del pipeline con OTRA identidad, fijar
    # AZURE_CLIENT_ID a una identidad que la app no tiene rompe
    # DefaultAzureCredential con "no managed identity with the specified client id".
    az containerapp identity assign -g "$RESOURCE_GROUP" -n "$APP_NAME" \
      --user-assigned "$LAB_IDENTITY_ID" --output none
    az containerapp registry set -g "$RESOURCE_GROUP" -n "$APP_NAME" \
      --server "$registry" --identity "$LAB_IDENTITY_ID" --output none
    # Imagen y variables en UNA sola invocacion (una sola revision nueva). Las
    # variables se reafirman en cada despliegue: si la API se recreo, su FQDN
    # cambio, y un banco de pruebas apuntando a la direccion anterior falla con un
    # error de red que parece un problema de la API.
    az containerapp update -g "$RESOURCE_GROUP" -n "$APP_NAME" --image "$imagen" \
      --set-env-vars \
        AZURE_CLIENT_ID="$LAB_CLIENT_ID" \
        CENTINELA_BASE_URL="$base_url" \
        CENTINELA_SCOPE="$scope" \
      --output none
  else
    log_info "Creando '$APP_NAME'"
    # min-replicas 0: es una herramienta de demostracion, no un servicio.
    # Mantenerla despierta consumiria credito sin motivo. El arranque en frio se
    # cubre con los margenes de espera de la propia aplicacion.
    az containerapp create \
      -g "$RESOURCE_GROUP" -n "$APP_NAME" \
      --environment "$ENVIRONMENT_NAME" \
      --image "$imagen" \
      --user-assigned "$LAB_IDENTITY_ID" \
      --registry-server "$registry" \
      --registry-identity "$LAB_IDENTITY_ID" \
      --target-port 8081 --ingress external \
      --cpu 0.5 --memory 1.0Gi \
      --min-replicas 0 --max-replicas 2 \
      --env-vars \
        AZURE_CLIENT_ID="$LAB_CLIENT_ID" \
        CENTINELA_BASE_URL="$base_url" \
        CENTINELA_SCOPE="$scope" \
      --output none
  fi
}

verificar() {
  local fqdn url codigo intento
  fqdn="$(az containerapp show -g "$RESOURCE_GROUP" -n "$APP_NAME" \
    --query properties.configuration.ingress.fqdn -o tsv)"
  [ -n "$fqdn" ] || die "El banco de pruebas no tiene ingreso configurado."
  url="https://${fqdn}/actuator/health/readiness"

  log_info "Sondeando $url ..."
  # Escala desde cero replicas: la primera peticion arranca el contenedor y puede
  # tardar. Un timeout corto reportaria un fallo que no existe.
  for intento in $(seq 1 24); do
    codigo="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$url" || echo 000)"
    if [ "$codigo" = "200" ]; then
      log_info "OK: el banco de pruebas responde 200."
      LAB_FQDN="$fqdn"
      return 0
    fi
    log_info "  intento $intento/24 -> HTTP $codigo"
    sleep 10
  done

  log_error "El banco de pruebas no respondio 200 tras 4 minutos."
  log_error "Registros:"
  az containerapp logs show -g "$RESOURCE_GROUP" -n "$APP_NAME" --tail 50 2>/dev/null || true
  return 1
}

# --- Main -----------------------------------------------------------------------

LAB_FQDN=""

main() {
  cargar_parametros
  derivar_nombres
  preflight

  if [ "$VALIDATE_ONLY" -eq 1 ]; then
    log_info "--validate-only: requisitos correctos. No se creo nada."
    return 0
  fi

  confirmar

  asegurar_identidad
  if [ "$SKIP_ROLE" -eq 1 ]; then
    log_warn "--skip-role: no se tocan las asignaciones de app role."
    advertir_si_falta_el_rol
  else
    conceder_app_roles
  fi
  if [ "$SKIP_ACRPULL" -eq 1 ]; then
    log_warn "--skip-acrpull: no se toca la asignacion AcrPull (aprovisionada a mano)."
  else
    conceder_acrpull
  fi

  if [ "$SKIP_IMAGE" -eq 1 ]; then
    log_warn "--skip-image: se despliega la etiqueta '$IMAGE_TAG' ya existente."
  else
    with_retry 2 construir_imagen || die "La construccion de la imagen fallo."
  fi

  desplegar_app
  verificar || die "El despliegue no quedo sano."

  log_info "═══════════════════════════════════════════════════════════════"
  log_info "  Banco de pruebas : https://${LAB_FQDN}"
  log_info "  Apunta a         : https://${API_FQDN}"
  log_info "  Identidad        : $IDENTITY_NAME (app roles: ${APP_ROLES_DEL_LAB[*]})"
  log_info "═══════════════════════════════════════════════════════════════"
}

main
