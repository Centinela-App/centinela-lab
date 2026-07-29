package com.centinelalab.domain;

import java.util.List;

/**
 * Los escenarios que se pueden lanzar contra Centinela.
 *
 * <p>Cada uno describe una <b>forma de trafico</b>, no un resultado. La expectativa que
 * acompaña a cada escenario es lo que la celula cree que Centinela deberia decidir; sirve para
 * contrastar, no para condicionar. Si el resultado real difiere de la expectativa, la
 * herramienta lo señala y ahi empieza la conversacion — puede que la regla este mal calibrada,
 * o puede que la expectativa fuera ingenua.
 */
public enum Scenario {

    /**
     * Control negativo. Sin el, todo lo demas es inservible: un detector que marca
     * absolutamente todo tambien "acierta" en los cinco escenarios fraudulentos.
     */
    NORMAL(
            "Transacción normal",
            "Un consumo corriente sobre una cuenta con historial estable: monto en línea con "
                    + "su promedio, ciudad habitual y comercio ordinario.",
            "No debería generar alerta ni abrir caso.",
            false,
            List.of()),

    VELOCITY(
            "Velocidad anómala",
            "Cuatro transacciones de la misma cuenta en cuestión de segundos, sobre un "
                    + "historial cuya cadencia habitual es de una cada varias horas.",
            "Debería activar la regla de velocidad.",
            true,
            List.of("velocity")),

    ATYPICAL_AMOUNT(
            "Monto atípico",
            "Un consumo que supera decenas de veces el promedio histórico de la cuenta, "
                    + "sembrado previamente con transacciones pequeñas.",
            "Debería activar la regla de monto atípico.",
            true,
            List.of("atypical-amount")),

    GEO_IMPOSSIBLE(
            "Geografía imposible",
            "Una transacción en Medellín y, once minutos después, otra en Madrid. El "
                    + "desplazamiento implicado es físicamente imposible.",
            "Debería activar la regla de geografía imposible.",
            true,
            List.of("geo-impossible")),

    RISKY_MERCHANT(
            "Comercio de riesgo",
            "Un consumo en un comercio cuya categoría figura en la lista de riesgo "
                    + "configurada en el motor.",
            "Debería activar la regla de comercio riesgoso.",
            true,
            List.of("risky-merchant")),

    COMBINED(
            "Fraude combinado",
            "Monto desmedido, en otro continente y minutos después de la anterior. Es el "
                    + "escenario que produce la explicación más completa.",
            "Debería activar varias reglas y superar el umbral con holgura.",
            true,
            List.of("atypical-amount", "geo-impossible")),

    UNREADABLE_DOCUMENT(
            "Documento ilegible",
            "Carga de un archivo corrupto como documento de identidad sobre un caso ya "
                    + "abierto. Escenario de fallo obligatorio de la sustentación.",
            "El caso debe seguir consultable y el analista debe recibir el resultado.",
            true,
            List.of()),

    SUSTAINED_LOAD(
            "Carga sostenida",
            "Ráfaga configurable de transacciones por segundo para forzar el escalado de la "
                    + "plataforma y observar el aumento y posterior reducción de instancias.",
            "El número de instancias debe subir bajo carga y bajar al cesar.",
            false,
            List.of());

    private final String label;
    private final String description;
    private final String expectation;
    private final boolean expectsCase;
    private final List<String> expectedRules;

    Scenario(String label, String description, String expectation,
             boolean expectsCase, List<String> expectedRules) {
        this.label = label;
        this.description = description;
        this.expectation = expectation;
        this.expectsCase = expectsCase;
        this.expectedRules = List.copyOf(expectedRules);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String expectation() {
        return expectation;
    }

    /** Lo que la celula espera. NO condiciona lo que Centinela decide. */
    public boolean expectsCase() {
        return expectsCase;
    }

    public List<String> expectedRules() {
        return expectedRules;
    }

    public String id() {
        return name();
    }
}
