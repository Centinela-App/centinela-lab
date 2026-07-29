package com.centinelalab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Banco de pruebas de Centinela.
 *
 * <p>Esta aplicacion <b>no</b> detecta fraude ni sabe como se detecta. Su unico trabajo es
 * fabricar transacciones con una forma concreta — una rafaga, un monto desmedido, un salto
 * geografico imposible — y entregarlas a Centinela por su API publica, igual que lo haria
 * un banco originador.
 *
 * <p>La decision de marcar o no marcar es enteramente de Centinela. Esta aplicacion despues
 * consulta el resultado y lo muestra, pero no lo provoca ni lo interpreta: si Centinela deja
 * pasar una transaccion que aqui se describe como fraudulenta, eso es informacion valiosa
 * sobre Centinela, no un fallo de esta herramienta.
 */
@SpringBootApplication
public class CentinelaLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(CentinelaLabApplication.class, args);
    }
}
