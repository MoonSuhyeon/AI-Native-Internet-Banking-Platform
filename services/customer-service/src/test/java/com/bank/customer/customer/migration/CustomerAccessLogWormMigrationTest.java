package com.bank.customer.customer.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerAccessLogWormMigrationTest {

    @Test
    void customerAccessLogHasUpdateAndDeleteBlockingTriggers() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/"
                + "V41__make_customer_access_log_insert_only.sql"));

        assertThat(migration)
                .contains("BEFORE UPDATE ON customer_access_log")
                .contains("BEFORE DELETE ON customer_access_log")
                .contains("INSERT-ONLY");
    }
}
