package com.centinelalab.infrastructure.web;

import com.centinelalab.application.LoadGenerationService;
import com.centinelalab.application.ScenarioExecutionService;
import com.centinelalab.domain.Scenario;
import com.centinelalab.domain.ScenarioResult;
import com.centinelalab.domain.W3CTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * La pantalla: un boton por causal de alerta y un panel con lo que Centinela decidio.
 *
 * <p>Los escenarios se ejecutan de forma sincrona a proposito. Cada uno tarda entre diez y
 * cuarenta segundos —siembra historial, espera al motor, espera al explicador— y durante una
 * sustentacion es preferible que la pagina se quede cargando a que el evaluador tenga que
 * refrescar para ver si ya termino.
 */
@Controller
public class LabController {

    private final ScenarioExecutionService escenarios;
    private final LoadGenerationService carga;
    private final String centinelaBaseUrl;

    public LabController(
            ScenarioExecutionService escenarios,
            LoadGenerationService carga,
            @Value("${centinela.base-url}") String centinelaBaseUrl) {
        this.escenarios = Objects.requireNonNull(escenarios);
        this.carga = Objects.requireNonNull(carga);
        this.centinelaBaseUrl = centinelaBaseUrl;
    }

    @GetMapping("/")
    public String inicio(Model model) {
        prepararModelo(model);
        return "index";
    }

    @PostMapping("/escenarios/{id}")
    public String ejecutar(@PathVariable String id, Model model) {
        Scenario scenario;
        try {
            scenario = Scenario.valueOf(id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escenario desconocido: " + id);
        }
        ScenarioResult resultado = escenarios.run(scenario);

        prepararModelo(model);
        model.addAttribute("resultado", resultado);
        model.addAttribute("traceId", W3CTrace.traceIdOf(resultado.traceparent()));
        return "index";
    }

    @PostMapping("/carga")
    public String generarCarga(
            @RequestParam(defaultValue = "20") int tasa,
            @RequestParam(defaultValue = "120") int duracion,
            Model model) {

        LoadGenerationService.LoadReport reporte = carga.generate(tasa, duracion);

        prepararModelo(model);
        model.addAttribute("reporteCarga", reporte);
        return "index";
    }

    private void prepararModelo(Model model) {
        List<Scenario> deteccion = Arrays.stream(Scenario.values())
                .filter(scenario -> scenario != Scenario.SUSTAINED_LOAD)
                .toList();

        model.addAttribute("escenarios", deteccion);
        model.addAttribute("centinelaBaseUrl", centinelaBaseUrl);
    }
}
