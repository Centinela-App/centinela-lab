package com.centinelalab.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Genera cabeceras {@code traceparent} en formato W3C Trace Context.
 *
 * <p>Cada escenario abre su propia traza. Asi, durante la sustentacion, el recorrido que se
 * muestra en la herramienta de monitoreo empieza en el clic del boton y no en el borde HTTP de
 * Centinela: incluye tambien al originador, que es lo que veria un banco con su cliente
 * instrumentado.
 *
 * <p>Solo genera; no interpreta. Esta aplicacion nunca recibe un {@code traceparent} ajeno.
 */
public final class W3CTrace {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private W3CTrace() {
    }

    /** Nueva traza muestreada: {@code 00-<32 hex>-<16 hex>-01}. */
    public static String newRootTraceparent() {
        return "00-" + randomHex(16) + "-" + randomHex(8) + "-01";
    }

    /** Extrae el {@code trace-id}, que es por lo que se busca en el panel de monitoreo. */
    public static String traceIdOf(String traceparent) {
        if (traceparent == null || traceparent.length() < 36) {
            return "";
        }
        return traceparent.substring(3, 35);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HEX.formatHex(buffer);
    }
}
