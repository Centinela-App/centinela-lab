package com.centinelalab.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabrica transacciones con la forma exacta que exige cada escenario.
 *
 * <p>Todos los escenarios anomalos <b>siembran historial</b> antes de enviar la transaccion
 * sospechosa. Es lo que distingue esta herramienta de un generador de trafico cualquiera: las
 * reglas de Centinela comparan contra el pasado de la cuenta, asi que un monto de cuatro
 * millones sobre una cuenta sin historial no activa nada. La anomalia solo existe en relacion
 * con lo habitual, y lo habitual hay que construirlo primero.
 *
 * <p>Cada ejecucion usa una cuenta nueva. Reutilizar la misma haria que el historial de una
 * demostracion contaminara la siguiente: tras lanzar tres veces el escenario de monto atipico,
 * el promedio de la cuenta ya incluiria los montos desmedidos y la regla dejaria de activarse.
 */
public final class TransactionFactory {

    /** Ciudades con coordenadas reales: la regla geografica calcula distancia de verdad. */
    private static final Place MEDELLIN = new Place("CO", "Medellín", "6.2442", "-75.5812");
    private static final Place MADRID = new Place("ES", "Madrid", "40.4168", "-3.7038");
    private static final Place BOGOTA = new Place("CO", "Bogotá", "4.7110", "-74.0721");

    private static final String CURRENCY = "COP";

    private TransactionFactory() {
    }

    /**
     * Construye la secuencia completa de un escenario.
     *
     * <p>El ultimo elemento es siempre la transaccion cuyo veredicto se observa; los anteriores
     * son el historial que la hace anomala.
     */
    public static List<TransactionPayload> build(Scenario scenario, String accountId) {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (scenario) {
            case NORMAL -> normal(accountId, now);
            case VELOCITY -> velocity(accountId, now);
            case ATYPICAL_AMOUNT -> atypicalAmount(accountId, now);
            case GEO_IMPOSSIBLE -> geoImpossible(accountId, now);
            case RISKY_MERCHANT -> riskyMerchant(accountId, now);
            case COMBINED -> combined(accountId, now);
            case UNREADABLE_DOCUMENT -> combined(accountId, now);
            case SUSTAINED_LOAD -> normal(accountId, now);
        };
    }

    // Historial tranquilo y un consumo mas del mismo tamano. Nada aqui deberia
    // llamar la atencion de ninguna regla.
    private static List<TransactionPayload> normal(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 4));
        secuencia.add(transaction(accountId, "48000", now, BOGOTA, "Supermercado La Esquina", "grocery"));
        return secuencia;
    }

    // Cuatro transacciones dentro de la ventana, sobre un historial de cadencia
    // espaciada para que el contraste con lo habitual sea medible.
    private static List<TransactionPayload> velocity(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 4));
        secuencia.add(transaction(accountId, "52000", now.minusMinutes(4), BOGOTA, "Cafetería Central", "restaurant"));
        secuencia.add(transaction(accountId, "47000", now.minusMinutes(2), BOGOTA, "Farmacia Norte", "pharmacy"));
        secuencia.add(transaction(accountId, "51000", now.minusMinutes(1), BOGOTA, "Librería Sur", "retail"));
        secuencia.add(transaction(accountId, "49000", now, BOGOTA, "Panadería El Trigo", "grocery"));
        return secuencia;
    }

    // Promedio de ~50 000 y un consumo de 4 200 000: 84 veces por encima.
    private static List<TransactionPayload> atypicalAmount(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 6));
        secuencia.add(transaction(accountId, "4200000", now, BOGOTA, "Electrónica Premium", "electronics"));
        return secuencia;
    }

    // Medellín y, once minutos despues, Madrid: unos 8 000 km a una velocidad imposible.
    private static List<TransactionPayload> geoImpossible(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 4));
        secuencia.add(transaction(accountId, "55000", now.minusMinutes(11), MEDELLIN, "Almacén Poblado", "retail"));
        secuencia.add(transaction(accountId, "62000", now, MADRID, "Tienda Gran Vía", "retail"));
        return secuencia;
    }

    // El comercio debe figurar en RISKY_MERCHANTS o su categoria en RISKY_CATEGORIES
    // del motor. Si el escenario no activa la regla, revisar esa configuracion antes
    // que el codigo.
    private static List<TransactionPayload> riskyMerchant(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 4));
        secuencia.add(transaction(accountId, "60000", now, BOGOTA, "Casino Royale", "gambling"));
        return secuencia;
    }

    // Monto desmedido y salto geografico a la vez: activa varias reglas y produce
    // la explicacion mas completa, que es la que conviene mostrar en vivo.
    private static List<TransactionPayload> combined(String accountId, OffsetDateTime now) {
        List<TransactionPayload> secuencia = new ArrayList<>(steadyHistory(accountId, now, 6));
        secuencia.add(transaction(accountId, "58000", now.minusMinutes(11), MEDELLIN, "Almacén Poblado", "retail"));
        secuencia.add(transaction(accountId, "4200000", now, MADRID, "Joyería Serrano", "jewelry"));
        return secuencia;
    }

    /**
     * Historial estable: montos alrededor de 50 000, una transaccion cada seis horas,
     * siempre en la misma ciudad. Es el "normal" contra el que se mide todo lo demas.
     */
    private static List<TransactionPayload> steadyHistory(String accountId, OffsetDateTime now, int cantidad) {
        List<TransactionPayload> historial = new ArrayList<>();
        for (int i = cantidad; i >= 1; i--) {
            String monto = String.valueOf(48000 + (i * 700));
            historial.add(transaction(accountId, monto, now.minusHours(6L * i), BOGOTA,
                    "Supermercado La Esquina", "grocery"));
        }
        return historial;
    }

    private static TransactionPayload transaction(
            String accountId, String amount, OffsetDateTime occurredAt,
            Place place, String merchantName, String merchantCategory) {
        return new TransactionPayload(
                "tx-" + UUID.randomUUID(),
                accountId,
                new BigDecimal(amount),
                CURRENCY,
                occurredAt,
                new TransactionPayload.Location(place.countryCode(), place.city(),
                        new BigDecimal(place.latitude()), new BigDecimal(place.longitude())),
                new TransactionPayload.Merchant(merchantName, merchantCategory));
    }

    public static String newAccountId() {
        return "acc-lab-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Place(String countryCode, String city, String latitude, String longitude) {
    }
}
