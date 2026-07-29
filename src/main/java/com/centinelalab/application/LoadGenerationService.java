package com.centinelalab.application;

import com.centinelalab.domain.TransactionFactory;
import com.centinelalab.domain.TransactionPayload;
import com.centinelalab.domain.W3CTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Genera carga sostenida contra la API de ingesta.
 *
 * <p>Su proposito es forzar el escalado de la plataforma y poder observarlo, no medir el
 * rendimiento de Centinela. Por eso se controla la <b>tasa de llegada</b> (transacciones por
 * segundo) y no la concurrencia: la regla de escalado de la API reacciona a peticiones
 * simultaneas, y una tasa constante produce una curva de replicas legible en lugar de un
 * pico instantaneo que sube y baja antes de que nadie lo vea.
 *
 * <p>Cada transaccion usa una cuenta distinta y una forma inocua. Deliberadamente: si la carga
 * generara casos, la demostracion de escalado ensuciaria la base de casos con miles de
 * registros de prueba y el analista no podria distinguir los reales.
 */
public final class LoadGenerationService {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerationService.class);

    private static final int MAX_RATE_PER_SECOND = 50;
    private static final int MAX_DURATION_SECONDS = 300;

    private final CentinelaClientPort centinela;

    public LoadGenerationService(CentinelaClientPort centinela) {
        this.centinela = Objects.requireNonNull(centinela, "centinela is required");
    }

    /**
     * @param transaccionesPorSegundo tasa de llegada; se acota a {@value #MAX_RATE_PER_SECOND}
     * @param duracionSegundos        duracion; se acota a {@value #MAX_DURATION_SECONDS}
     */
    public LoadReport generate(int transaccionesPorSegundo, int duracionSegundos) {
        // Los topes existen por el credito: el proyecto tiene un limite de 60 USD y la
        // generacion de carga es, junto con la telemetria, lo que mas lo consume. Un cero
        // de mas en un formulario no debe costar el presupuesto de la semana.
        int tasa = Math.max(1, Math.min(transaccionesPorSegundo, MAX_RATE_PER_SECOND));
        int duracion = Math.max(1, Math.min(duracionSegundos, MAX_DURATION_SECONDS));

        Instant inicio = Instant.now();
        AtomicInteger aceptadas = new AtomicInteger();
        AtomicInteger rechazadas = new AtomicInteger();

        ExecutorService emisores = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int segundo = 0; segundo < duracion; segundo++) {
                Instant inicioSegundo = Instant.now();

                for (int i = 0; i < tasa; i++) {
                    emisores.submit(() -> enviarUna(aceptadas, rechazadas));
                }

                // Se descuenta lo que tardo en encolar el lote para que la tasa real se
                // acerque a la pedida en vez de degradarse a cada segundo que pasa.
                long restanteMs = 1000 - Duration.between(inicioSegundo, Instant.now()).toMillis();
                if (restanteMs > 0) {
                    dormir(restanteMs);
                }
            }

            emisores.shutdown();
            if (!emisores.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Quedaron envios en vuelo al terminar la ventana de carga.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emisores.shutdownNow();
        }

        long transcurridoMs = Duration.between(inicio, Instant.now()).toMillis();
        LoadReport reporte = new LoadReport(
                tasa, duracion, aceptadas.get(), rechazadas.get(), transcurridoMs);

        log.info("Carga completada: {} aceptadas, {} rechazadas en {} ms",
                reporte.aceptadas(), reporte.rechazadas(), transcurridoMs);
        return reporte;
    }

    private void enviarUna(AtomicInteger aceptadas, AtomicInteger rechazadas) {
        try {
            List<TransactionPayload> secuencia =
                    TransactionFactory.build(com.centinelalab.domain.Scenario.SUSTAINED_LOAD,
                            TransactionFactory.newAccountId());
            TransactionPayload ultima = secuencia.get(secuencia.size() - 1);
            centinela.submitTransaction(ultima, W3CTrace.newRootTraceparent());
            aceptadas.incrementAndGet();
        } catch (RuntimeException exception) {
            // Un 429 durante la carga NO es un fallo: es el limitador de tasa de Centinela
            // funcionando. Se cuenta aparte para poder distinguirlo de un error real al
            // interpretar la curva de escalado.
            rechazadas.incrementAndGet();
        }
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resumen de una ventana de carga. */
    public record LoadReport(
            int tasaPorSegundo,
            int duracionSegundos,
            int aceptadas,
            int rechazadas,
            long transcurridoMs) {

        public int total() {
            return aceptadas + rechazadas;
        }

        public double tasaEfectiva() {
            return transcurridoMs == 0 ? 0 : total() * 1000.0 / transcurridoMs;
        }
    }
}
