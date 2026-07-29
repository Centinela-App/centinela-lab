package com.centinelalab.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Cuerpo de {@code POST /api/v1/transactions} tal como lo define Centinela.
 *
 * <p>Es una copia deliberada del contrato, no una dependencia sobre el. Compartir la clase
 * obligaria a publicar un artefacto comun y acoplaria el despliegue de las dos aplicaciones —
 * exactamente lo que el aislamiento entre repositorios pretende evitar.
 *
 * <p>La contrapartida es real: si Centinela cambia el contrato, esta copia se entera al
 * fallar. Se acepta porque la API rechaza toda propiedad no declarada, asi que la divergencia
 * se manifiesta como un {@code 400} inmediato y no como un dato silenciosamente ignorado.
 */
public record TransactionPayload(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        OffsetDateTime occurredAt,
        Location location,
        Merchant merchant) {

    public record Location(String countryCode, String city, BigDecimal latitude, BigDecimal longitude) {
    }

    public record Merchant(String name, String category) {
    }
}
