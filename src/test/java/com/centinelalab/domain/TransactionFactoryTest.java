package com.centinelalab.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estas pruebas verifican que cada escenario genera <b>de verdad</b> la forma de tráfico que
 * promete. Es la única garantía que esta herramienta puede dar por sí sola: si el escenario de
 * monto atípico enviara un monto normal, el resultado sería "Centinela no alertó" y la célula
 * pasaría horas buscando el fallo en el motor.
 */
class TransactionFactoryTest {

    @Test
    void every_anomalous_scenario_seeds_history_first() {
        // Sin historial no hay anomalía: las reglas comparan contra el pasado de la cuenta,
        // así que un monto desmedido sobre una cuenta nueva no activa nada.
        for (Scenario scenario : Scenario.values()) {
            List<TransactionPayload> secuencia =
                    TransactionFactory.build(scenario, TransactionFactory.newAccountId());

            assertThat(secuencia)
                    .as("el escenario %s debe sembrar historial antes de la transacción observada", scenario)
                    .hasSizeGreaterThan(1);
        }
    }

    @Test
    void all_transactions_of_a_scenario_belong_to_the_same_account() {
        // Si el historial fuera de otra cuenta, no serviría de referencia para ninguna regla.
        String cuenta = TransactionFactory.newAccountId();

        assertThat(TransactionFactory.build(Scenario.ATYPICAL_AMOUNT, cuenta))
                .allMatch(transaccion -> transaccion.accountId().equals(cuenta));
    }

    @Test
    void transaction_identifiers_are_unique_within_a_scenario() {
        List<TransactionPayload> secuencia =
                TransactionFactory.build(Scenario.VELOCITY, TransactionFactory.newAccountId());

        assertThat(secuencia).extracting(TransactionPayload::transactionId).doesNotHaveDuplicates();
    }

    @Test
    void atypical_amount_really_is_atypical() {
        List<TransactionPayload> secuencia =
                TransactionFactory.build(Scenario.ATYPICAL_AMOUNT, TransactionFactory.newAccountId());

        TransactionPayload observada = secuencia.get(secuencia.size() - 1);
        BigDecimal promedioHistorial = secuencia.subList(0, secuencia.size() - 1).stream()
                .map(TransactionPayload::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(secuencia.size() - 1L), 2, java.math.RoundingMode.HALF_UP);

        // El umbral por defecto de la regla es 3x. Se exige un margen muy superior para que el
        // escenario no dependa de la calibración exacta del motor.
        assertThat(observada.amount())
                .isGreaterThan(promedioHistorial.multiply(BigDecimal.valueOf(20)));
    }

    @Test
    void velocity_puts_four_transactions_inside_the_window() {
        List<TransactionPayload> secuencia =
                TransactionFactory.build(Scenario.VELOCITY, TransactionFactory.newAccountId());

        TransactionPayload observada = secuencia.get(secuencia.size() - 1);
        long dentroDeLaVentana = secuencia.stream()
                .filter(transaccion -> Duration.between(
                        transaccion.occurredAt(), observada.occurredAt()).toMinutes() <= 60)
                .count();

        // La regla exige superar 3 en la ventana; con 4 se activa con margen de uno.
        assertThat(dentroDeLaVentana).isGreaterThanOrEqualTo(4);
    }

    @Test
    void geo_impossible_really_is_impossible() {
        List<TransactionPayload> secuencia =
                TransactionFactory.build(Scenario.GEO_IMPOSSIBLE, TransactionFactory.newAccountId());

        TransactionPayload observada = secuencia.get(secuencia.size() - 1);
        TransactionPayload anterior = secuencia.get(secuencia.size() - 2);

        assertThat(anterior.location().city()).isEqualTo("Medellín");
        assertThat(observada.location().city()).isEqualTo("Madrid");

        long minutos = Duration.between(anterior.occurredAt(), observada.occurredAt()).toMinutes();
        assertThat(minutos).isBetween(1L, 30L);
    }

    @Test
    void the_normal_scenario_stays_within_the_account_baseline() {
        // El control negativo tiene que ser genuinamente inocuo. Si activara una regla, no
        // serviría para distinguir un detector que funciona de uno que marca todo.
        List<TransactionPayload> secuencia =
                TransactionFactory.build(Scenario.NORMAL, TransactionFactory.newAccountId());

        TransactionPayload observada = secuencia.get(secuencia.size() - 1);
        BigDecimal maximoHistorial = secuencia.subList(0, secuencia.size() - 1).stream()
                .map(TransactionPayload::amount)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        assertThat(observada.amount()).isLessThanOrEqualTo(maximoHistorial.multiply(BigDecimal.valueOf(2)));
        assertThat(secuencia).allMatch(transaccion -> "Bogotá".equals(transaccion.location().city()));
    }

    @Test
    void each_run_uses_a_fresh_account() {
        // Reutilizar la cuenta haría que una demostración contaminara la siguiente: tras tres
        // ejecuciones de monto atípico, el promedio ya incluiría los montos desmedidos.
        assertThat(TransactionFactory.newAccountId()).isNotEqualTo(TransactionFactory.newAccountId());
    }
}
