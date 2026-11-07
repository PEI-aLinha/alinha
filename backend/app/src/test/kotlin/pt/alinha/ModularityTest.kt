package pt.alinha

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Garante as regras da arquitetura em cada build (corre no CI do repo
 * principal E no CI de cada plugin):
 *  - nenhum plugin acede a classes internas de outro módulo;
 *  - não há ciclos entre módulos;
 *  - só se usam os tipos públicos e as «named interfaces» (api, events, core::*).
 */
class ModularityTest {

    private val modules = ApplicationModules.of(AlinhaApplication::class.java)

    @Test
    fun `respeita as fronteiras entre modulos`() {
        modules.verify()
    }

    @Test
    fun `gera documentacao dos modulos`() {
        // build/spring-modulith-docs — diagramas C4/PlantUML para o relatório
        Documenter(modules).writeDocumentation()
    }
}
