package com.jjt.ingsis.snippets

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:snippets;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
    ],
)
class SnippetsServiceApplicationTest
    @Autowired
    constructor(
        private val dataSource: DataSource,
    ) {
        @Test
        fun contextLoadsWithDatabaseConnection() {
            dataSource.connection.use { connection ->
                assertTrue(connection.isValid(1))
            }
        }
    }
