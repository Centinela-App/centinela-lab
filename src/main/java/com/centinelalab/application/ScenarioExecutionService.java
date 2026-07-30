package com.centinelalab.application;

import com.centinelalab.domain.Scenario;
import com.centinelalab.domain.ScenarioResult;
import com.centinelalab.domain.TransactionFactory;
import com.centinelalab.domain.TransactionPayload;
import com.centinelalab.domain.W3CTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ejecuta un escenario de principio a fin: siembra historial, envia la transaccion
 * observada, espera a que Centinela decida y recoge el resultado.
 *
 * <p><b>La espera es inherente al diseño de Centinela, no un defecto de esta herramienta.</b>
 * La ingesta responde {@code 202} antes de analizar nada — es justamente lo que demuestra que
 * el cliente no espera por el analisis. Como consecuencia, el veredicto no existe todavia
 * cuando termina la peticion y hay que consultarlo despues.
 *
 * <p>El sondeo tiene dos fases porque hay dos plazos distintos: el score aparece en segundos,
 * la explicacion tarda mas porque el explicador la genera en un ciclo aparte. Esperar el
 * mismo tiempo para ambos alargaria innecesariamente cada demostracion.
 */
public final class ScenarioExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioExecutionService.class);

    private static final byte[] DOCUMENTO_CORRUPTO =
            "%PDF-1.7 este archivo dice ser un PDF y no lo es".getBytes(StandardCharsets.ISO_8859_1);

    private final CentinelaClientPort centinela;
    private final Duration esperaAnalisis;
    private final Duration esperaExplicacion;
    private final Duration intervaloSondeo;

    public ScenarioExecutionService(
            CentinelaClientPort centinela,
            Duration esperaAnalisis,
            Duration esperaExplicacion,
            Duration intervaloSondeo) {
        this.centinela = Objects.requireNonNull(centinela, "centinela is required");
        this.esperaAnalisis = esperaAnalisis;
        this.esperaExplicacion = esperaExplicacion;
        this.intervaloSondeo = intervaloSondeo;
    }

    public ScenarioResult run(Scenario scenario) {
        Instant inicio = Instant.now();
        String traceparent = W3CTrace.newRootTraceparent();
        String accountId = TransactionFactory.newAccountId();

        try {
            List<TransactionPayload> secuencia = TransactionFactory.build(scenario, accountId);
            List<String> enviadas = new ArrayList<>();

            for (int i = 0; i < secuencia.size(); i++) {
                // El historial tiene que estar ASENTADO antes de enviar la transaccion
                // observada. Los eventos se procesan en paralelo del otro lado: sin esta
                // espera, el motor puntua la observada contra un pasado que todavia no es
                // visible y ninguna regla se activa (carrera observada en despliegue real:
                // las ocho transacciones del escenario de velocidad llegaron a la vez y
                // todas puntuaron 0 sobre un historial vacio).
                boolean esLaObservada = i == secuencia.size() - 1;
                if (esLaObservada && !enviadas.isEmpty()) {
                    for (String previa : enviadas) {
                        sondear(() -> centinela.fetchAnalysis(previa, traceparent), esperaAnalisis);
                    }
                }
                centinela.submitTransaction(secuencia.get(i), traceparent);
                enviadas.add(secuencia.get(i).transactionId());
            }

            // La ultima es la que se observa; las anteriores solo construyen el pasado
            // de la cuenta contra el que las reglas comparan.
            String observada = enviadas.get(enviadas.size() - 1);
            log.info("Escenario {} lanzado: cuenta={} observada={} traceparent={}",
                    scenario, accountId, observada, traceparent);

            if (scenario == Scenario.UNREADABLE_DOCUMENT) {
                return escenarioDocumentoIlegible(scenario, inicio, traceparent, enviadas, observada);
            }

            return recogerResultado(scenario, inicio, traceparent, enviadas, observada,
                    scenario.expectsCase());

        } catch (RuntimeException exception) {
            log.error("Escenario {} fallo", scenario, exception);
            return ScenarioResult.failed(scenario, inicio, transcurrido(inicio), mensaje(exception));
        }
    }

    /**
     * Escenario de fallo obligatorio: se abre un caso legitimo y despues se le adjunta un
     * archivo corrupto. Lo que se comprueba no es que la extraccion funcione — se sabe que no
     * puede — sino que el caso <b>sigue consultable</b> y que el resultado del intento queda
     * registrado para el analista.
     */
    private ScenarioResult escenarioDocumentoIlegible(
            Scenario scenario, Instant inicio, String traceparent,
            List<String> enviadas, String observada) {

        ScenarioResult conCaso = recogerResultado(scenario, inicio, traceparent, enviadas, observada, true);
        if (conCaso.caseStatus() == null) {
            return new ScenarioResult(scenario, inicio, transcurrido(inicio), enviadas, observada,
                    traceparent, conCaso.score(), conCaso.threshold(), conCaso.flagged(),
                    conCaso.triggeredRules(), null, null, null, List.of(),
                    "No se abrió caso, así que no hay dónde adjuntar el documento. "
                            + "Revise el umbral de scoring configurado.");
        }

        centinela.uploadDocument(observada, "documento-corrupto.pdf",
                "application/pdf", DOCUMENTO_CORRUPTO, traceparent);

        // El extractor corre en un ciclo aparte; se espera a que registre su desenlace.
        dormir(intervaloSondeo.multipliedBy(3));
        return recogerResultado(scenario, inicio, traceparent, enviadas, observada, true);
    }

    private ScenarioResult recogerResultado(
            Scenario scenario, Instant inicio, String traceparent,
            List<String> enviadas, String observada, boolean esperarExplicacion) {

        Map<String, Object> analisis = sondear(
                () -> centinela.fetchAnalysis(observada, traceparent), esperaAnalisis)
                .orElse(Map.of());

        Map<String, Object> caso = Map.of();
        boolean marcada = Boolean.TRUE.equals(analisis.get("flagged"));
        if (marcada) {
            caso = sondear(() -> centinela.fetchCase(observada, traceparent), esperaAnalisis)
                    .orElse(Map.of());

            if (esperarExplicacion && "PENDING".equals(caso.get("explanationState"))) {
                // Se reintenta con el plazo largo: si el explicador esta detenido, se
                // agotara y el caso quedara mostrado como PENDING, que es exactamente el
                // comportamiento que la sustentacion debe evidenciar.
                Map<String, Object> conExplicacion = sondearHasta(
                        () -> centinela.fetchCase(observada, traceparent),
                        estado -> !"PENDING".equals(estado.get("explanationState")),
                        esperaExplicacion).orElse(caso);
                caso = conExplicacion;
            }
        }

        return componer(scenario, inicio, traceparent, enviadas, observada, analisis, caso);
    }

    @SuppressWarnings("unchecked")
    private ScenarioResult componer(
            Scenario scenario, Instant inicio, String traceparent,
            List<String> enviadas, String observada,
            Map<String, Object> analisis, Map<String, Object> caso) {

        List<ScenarioResult.TriggeredRule> reglas = new ArrayList<>();
        Object activadas = analisis.get("triggeredRules");
        if (activadas instanceof List<?> lista) {
            for (Object elemento : lista) {
                if (elemento instanceof Map<?, ?> regla) {
                    Object identificador = regla.get("ruleId");
                    Object nombre = regla.get("ruleName");
                    Object puntos = regla.get("points");
                    reglas.add(new ScenarioResult.TriggeredRule(
                            String.valueOf(identificador),
                            nombre == null ? String.valueOf(identificador) : String.valueOf(nombre),
                            puntos instanceof Number numero ? numero.intValue() : 0));
                }
            }
        }

        List<ScenarioResult.DocumentOutcome> documentos = new ArrayList<>();
        Object adjuntos = caso.get("verificationDocuments");
        if (adjuntos instanceof List<?> lista) {
            for (Object elemento : lista) {
                if (elemento instanceof Map<?, ?> documento) {
                    documentos.add(new ScenarioResult.DocumentOutcome(
                            texto(documento.get("state")),
                            texto(documento.get("failureReason")),
                            texto(documento.get("fullName")),
                            texto(documento.get("documentNumber"))));
                }
            }
        }

        return new ScenarioResult(
                scenario,
                inicio,
                transcurrido(inicio),
                enviadas,
                observada,
                traceparent,
                entero(analisis.get("score")),
                entero(analisis.get("threshold")),
                (Boolean) analisis.get("flagged"),
                reglas,
                texto(caso.get("status")),
                texto(caso.get("explanationState")),
                texto(caso.get("explanation")),
                documentos,
                null);
    }

    private Optional<Map<String, Object>> sondear(
            java.util.function.Supplier<Optional<Map<String, Object>>> consulta, Duration limite) {
        return sondearHasta(consulta, resultado -> true, limite);
    }

    private Optional<Map<String, Object>> sondearHasta(
            java.util.function.Supplier<Optional<Map<String, Object>>> consulta,
            java.util.function.Predicate<Map<String, Object>> condicion,
            Duration limite) {

        Instant fin = Instant.now().plus(limite);
        Optional<Map<String, Object>> ultimo = Optional.empty();
        while (Instant.now().isBefore(fin)) {
            Optional<Map<String, Object>> actual = consulta.get();
            if (actual.isPresent()) {
                ultimo = actual;
                if (condicion.test(actual.get())) {
                    return actual;
                }
            }
            dormir(intervaloSondeo);
        }
        return ultimo;
    }

    private static void dormir(Duration duracion) {
        try {
            Thread.sleep(duracion.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long transcurrido(Instant inicio) {
        return Duration.between(inicio, Instant.now()).toMillis();
    }

    private static Integer entero(Object valor) {
        return valor instanceof Number numero ? numero.intValue() : null;
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private static String mensaje(RuntimeException exception) {
        String detalle = exception.getMessage();
        return detalle == null || detalle.isBlank() ? exception.getClass().getSimpleName() : detalle;
    }
}
