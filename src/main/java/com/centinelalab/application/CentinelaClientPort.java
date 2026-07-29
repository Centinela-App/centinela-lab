package com.centinelalab.application;

import com.centinelalab.domain.TransactionPayload;

import java.util.Map;
import java.util.Optional;

/**
 * Todo lo que esta aplicacion sabe hacer contra Centinela.
 *
 * <p>Cuatro operaciones, todas por HTTP publico. No hay acceso a su base de datos, ni a sus
 * colas, ni a su telemetria: si algo no se puede averiguar por la API, esta herramienta no lo
 * sabe. Esa restriccion es intencionada — obliga a que la API exponga lo que un cliente real
 * necesitaria.
 */
public interface CentinelaClientPort {

    /** Envia una transaccion. Centinela responde 202 antes de analizarla. */
    void submitTransaction(TransactionPayload transaction, String traceparent);

    /** Decision del motor: score, umbral y reglas activadas. Vacio si aun no se puntuo. */
    Optional<Map<String, Object>> fetchAnalysis(String transactionId, String traceparent);

    /** Caso abierto con su explicacion. Vacio si la transaccion no fue marcada. */
    Optional<Map<String, Object>> fetchCase(String transactionId, String traceparent);

    /** Carga un documento de verificacion asociado a una transaccion. */
    void uploadDocument(String transactionId, String fileName, String contentType,
                        byte[] content, String traceparent);
}
