package com.centinelalab.domain;

import java.time.Instant;
import java.util.List;

/**
 * Lo que ocurrio al lanzar un escenario.
 *
 * <p>Separa deliberadamente tres cosas que es facil confundir: lo que se envio, lo que
 * Centinela decidio, y si eso coincide con lo que se esperaba. La ultima es una observacion de
 * la celula, no un veredicto del sistema.
 */
public record ScenarioResult(
        Scenario scenario,
        Instant startedAt,
        long elapsedMillis,
        List<String> transactionIds,
        String observedTransactionId,
        String traceparent,
        Integer score,
        Integer threshold,
        Boolean flagged,
        List<TriggeredRule> triggeredRules,
        String caseStatus,
        String explanationState,
        String explanation,
        List<DocumentOutcome> documents,
        String error) {

    public record TriggeredRule(String ruleId, String ruleName, int points) {
    }

    public record DocumentOutcome(String state, String failureReason, String fullName, String documentNumber) {
    }

    public static ScenarioResult failed(Scenario scenario, Instant startedAt, long elapsed, String error) {
        return new ScenarioResult(scenario, startedAt, elapsed, List.of(), null, null,
                null, null, null, List.of(), null, null, null, List.of(), error);
    }

    /**
     * {@code true} si lo observado coincide con lo que el escenario anticipaba.
     *
     * <p>Un desajuste no significa que la herramienta fallara. Significa que Centinela decidio
     * algo distinto de lo previsto, que es exactamente el tipo de hallazgo que justifica que
     * esta aplicacion exista.
     */
    public boolean matchesExpectation() {
        if (error != null) {
            return false;
        }
        boolean hayCaso = caseStatus != null;
        if (hayCaso != scenario.expectsCase()) {
            return false;
        }
        return scenario.expectedRules().stream()
                .allMatch(esperada -> triggeredRules.stream()
                        .anyMatch(activada -> activada.ruleId().equalsIgnoreCase(esperada)));
    }

    /** Texto corto para la insignia de la interfaz. */
    public String verdict() {
        if (error != null) {
            return "ERROR";
        }
        if (Boolean.TRUE.equals(flagged)) {
            return "MARCADA";
        }
        return "NO MARCADA";
    }

    public boolean explanationPending() {
        return "PENDING".equals(explanationState);
    }
}
