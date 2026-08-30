package com.offcanon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OffcanonApplicationTest {
    @TempDir
    static Path dataRoot;

    @DynamicPropertySource
    static void isolateRuntimeData(DynamicPropertyRegistry registry) {
        registry.add("offcanon.data-root", () -> dataRoot.toString());
    }

    @Test
    void contextStartsWithLocalAdapters() {
    }
}
