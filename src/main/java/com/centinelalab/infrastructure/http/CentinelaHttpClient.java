package com.centinelalab.infrastructure.http;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.centinelalab.application.CentinelaClientPort;
import com.centinelalab.domain.TransactionPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cliente HTTP de Centinela.
 *
 * <p><b>Sin credenciales almacenadas.</b> El token lo emite Entra ID contra la identidad de
 * esta aplicacion: Managed Identity cuando corre en Azure, o las variables de entorno del
 * desarrollador en local. En ningun caso hay una contrasena en el repositorio ni en la imagen.
 *
 * <p>El {@code traceparent} se envia en cada peticion para que el recorrido registrado en la
 * telemetria de Centinela empiece <b>aqui</b> y no en su borde HTTP. Durante la sustentacion
 * eso permite mostrar una traza que incluye al originador, que es lo que ocurriria con un
 * cliente real instrumentado.
 */
public final class CentinelaHttpClient implements CentinelaClientPort {

    private static final Logger log = LoggerFactory.getLogger(CentinelaHttpClient.class);

    private final RestClient restClient;
    private final TokenCredential credential;
    private final String scope;

    public CentinelaHttpClient(RestClient restClient, TokenCredential credential, String scope) {
        this.restClient = Objects.requireNonNull(restClient, "restClient is required");
        this.credential = Objects.requireNonNull(credential, "credential is required");
        this.scope = Objects.requireNonNull(scope, "scope is required");
    }

    @Override
    public void submitTransaction(TransactionPayload transaction, String traceparent) {
        restClient.post()
                .uri("/api/v1/transactions")
                .headers(headers -> authenticate(headers, traceparent))
                .contentType(MediaType.APPLICATION_JSON)
                .body(transaction)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Optional<Map<String, Object>> fetchAnalysis(String transactionId, String traceparent) {
        return get("/api/v1/transactions/" + transactionId + "/analysis", traceparent);
    }

    @Override
    public Optional<Map<String, Object>> fetchCase(String transactionId, String traceparent) {
        return get("/api/v1/cases/" + transactionId, traceparent);
    }

    @Override
    public void uploadDocument(String transactionId, String fileName, String contentType,
                               byte[] content, String traceparent) {
        MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
        parte(partes, fileName, contentType, content);

        restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/verification-documents")
                        .queryParam("transactionId", transactionId)
                        .build())
                .headers(headers -> authenticate(headers, traceparent))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(partes)
                .retrieve()
                .toBodilessEntity();
    }

    private void parte(MultiValueMap<String, Object> partes, String fileName,
                       String contentType, byte[] content) {
        ByteArrayResource recurso = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        partes.add("file", recurso);
        if (contentType != null) {
            log.debug("Documento '{}' declarado como {}", fileName, contentType);
        }
    }

    /**
     * Un {@code 404} NO es un error: significa "esta transaccion no fue marcada" o "el motor
     * todavia no la ha puntuado". Tratarlo como excepcion obligaria a envolver cada consulta en
     * un try/catch y convertiria el caso mas comun del escenario normal en una ruta de error.
     */
    private Optional<Map<String, Object>> get(String path, String traceparent) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cuerpo = restClient.get()
                    .uri(path)
                    .headers(headers -> authenticate(headers, traceparent))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> { })
                    .body(Map.class);
            return Optional.ofNullable(cuerpo);
        } catch (RuntimeException exception) {
            log.debug("Consulta a {} sin resultado: {}", path, exception.getMessage());
            return Optional.empty();
        }
    }

    private void authenticate(HttpHeaders headers, String traceparent) {
        // El SDK cachea el token y solo lo renueva cuando esta por expirar, asi que esta
        // llamada no supone una peticion a Entra por cada transaccion enviada.
        String token = credential
                .getTokenSync(new TokenRequestContext().addScopes(scope))
                .getToken();
        headers.setBearerAuth(token);
        if (traceparent != null) {
            headers.set("traceparent", traceparent);
        }
    }
}
