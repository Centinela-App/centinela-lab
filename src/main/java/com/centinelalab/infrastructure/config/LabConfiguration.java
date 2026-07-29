package com.centinelalab.infrastructure.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.centinelalab.application.CentinelaClientPort;
import com.centinelalab.application.LoadGenerationService;
import com.centinelalab.application.ScenarioExecutionService;
import com.centinelalab.infrastructure.http.CentinelaHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Cableado de la aplicacion de pruebas. */
@Configuration
public class LabConfiguration {

    /**
     * {@code DefaultAzureCredential} resuelve la identidad segun donde corra: Managed Identity
     * en Azure, variables de entorno o sesion de Azure CLI en la maquina del desarrollador. En
     * ninguno de los dos casos hay una credencial escrita en el repositorio o en la imagen.
     */
    @Bean
    public TokenCredential tokenCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    public RestClient centinelaRestClient(@Value("${centinela.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Timeouts explicitos: sin ellos, una API que no responde deja la peticion del
        // navegador colgada indefinidamente y la demostracion se queda en blanco sin
        // explicacion.
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Bean
    public CentinelaClientPort centinelaClient(
            RestClient centinelaRestClient,
            TokenCredential tokenCredential,
            @Value("${centinela.scope}") String scope) {
        return new CentinelaHttpClient(centinelaRestClient, tokenCredential, scope);
    }

    @Bean
    public ScenarioExecutionService scenarioExecutionService(
            CentinelaClientPort centinelaClient,
            @Value("${centinela.espera.analisis-segundos:45}") int analisis,
            @Value("${centinela.espera.explicacion-segundos:60}") int explicacion,
            @Value("${centinela.espera.intervalo-segundos:3}") int intervalo) {
        return new ScenarioExecutionService(
                centinelaClient,
                Duration.ofSeconds(analisis),
                Duration.ofSeconds(explicacion),
                Duration.ofSeconds(intervalo));
    }

    @Bean
    public LoadGenerationService loadGenerationService(CentinelaClientPort centinelaClient) {
        return new LoadGenerationService(centinelaClient);
    }
}
