package org.egov.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The "wall" that replaces the network. {@link ApplicationModules#verify()} fails the
 * build if any module reaches into another module's internals or forms a cycle.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(PlatformApplication.class);

    @Test
    void printModules() {
        modules.forEach(System.out::println);
    }

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
