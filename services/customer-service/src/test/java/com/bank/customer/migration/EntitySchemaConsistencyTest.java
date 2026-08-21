package com.bank.customer.migration;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔티티가 기대하는 테이블이 마이그레이션에 실제로 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 실제로 이런 일이 있었다. V7 이 {@code auth_token} 을 만들었고
 * V9 가 "설계문서에 없고 참조 코드도 0건" 이라며 지웠다. 그 판단은 그때는 옳았다.
 * 그 뒤 자금이동 step-up 인증이 들어오면서 {@code AuthToken} 엔티티가 그 테이블을
 * 쓰기 시작했는데, 되살리는 마이그레이션이 함께 오지 않았다.
 *
 * <p>결과는 조용했다. <b>컴파일도 되고 애플리케이션도 뜨고 화면도 멀쩡했다.</b>
 * 다만 승인 토큰 발급이 항상 500 이었고, 토큰을 얻을 수 없으니 소액을 넘는 이체는
 * 아무도 할 수 없었다. 실제로 이체를 태워 보기 전까지 아무도 몰랐다.
 *
 * <p>단위 테스트는 Flyway 를 끄고 H2 엔티티 DDL 로 돌기 때문에 이 어긋남을 볼 수 없다 —
 * 엔티티가 곧 스키마라서 언제나 일치한다. 그래서 실제 마이그레이션을 올린 Postgres 와
 * 대조해야 한다.
 *
 * <p>컬럼 단위까지 보지는 않는다. 테이블 존재만으로도 이 부류의 사고는 대부분 걸리고,
 * 컬럼까지 강제하면 마이그레이션이 앞서 나가는 정상적인 상황에서 거짓 실패가 난다.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntitySchemaConsistencyTest {

    private static final String ENTITY_BASE_PACKAGE = "com.bank.customer";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("모든 @Entity 의 테이블이 마이그레이션 결과에 존재한다")
    void everyEntityHasATable() throws Exception {
        Set<String> actual = actualTableNames();
        List<String> missing = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            String table = tableNameOf(entity);
            if (!actual.contains(table.toLowerCase())) {
                missing.add(entity.getSimpleName() + " → " + table);
            }
        }

        assertThat(missing)
                .as("엔티티가 쓰는 테이블을 만드는 마이그레이션이 없다. "
                        + "컴파일과 기동은 통과하고 그 기능을 실제로 써 봐야 드러난다")
                .isEmpty();
    }

    @Test
    @DisplayName("모든 @Entity 의 컬럼이 마이그레이션 결과에 존재한다")
    void everyEntityFieldHasAColumn() throws Exception {
        Map<String, Set<String>> actual = actualColumns();
        List<String> missing = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            String table = tableNameOf(entity).toLowerCase();
            Set<String> columns = actual.get(table);
            if (columns == null) {
                // 테이블 자체가 없는 것은 위 테스트가 잡는다. 여기서 또 세지 않는다.
                continue;
            }
            for (String column : columnNamesOf(entity)) {
                if (!columns.contains(column.toLowerCase())) {
                    missing.add(entity.getSimpleName() + "." + column + " → " + table);
                }
            }
        }

        assertThat(missing)
                .as("엔티티가 읽는 컬럼을 만드는 마이그레이션이 없다. "
                        + "단위 테스트는 H2 가 **엔티티에서** 스키마를 만들어 언제나 통과하고, "
                        + "실제로는 그 기능을 처음 부르는 순간 500 이 난다. "
                        + "상속(BaseEntity)으로 들어오는 감사 컬럼이 특히 잘 빠진다")
                .isEmpty();
    }

    /**
     * 엔티티가 실제로 매핑하는 컬럼 이름.
     *
     * <p>상속 계층을 끝까지 올라간다 — {@code BaseEntity} 의 감사 컬럼
     * ({@code created_by}·{@code version} 등)이 여기서 들어오고, 실제로 그것들이
     * 빠져서 목록 조회가 500 이 났다.
     *
     * <p>연관 필드({@code @OneToMany}·{@code @ManyToMany})와 {@code @Transient} 는
     * 컬럼이 아니므로 뺀다. {@code @ManyToOne} 은 조인 컬럼을 만들지만 이름 규칙이
     * 갈려 여기서는 보지 않는다 — 놓치는 쪽이 거짓 경보보다 낫다.
     */
    private List<String> columnNamesOf(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.isAnnotationPresent(jakarta.persistence.Transient.class)
                        || f.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                        || f.isAnnotationPresent(jakarta.persistence.ManyToMany.class)
                        || f.isAnnotationPresent(jakarta.persistence.ManyToOne.class)
                        || f.isAnnotationPresent(jakarta.persistence.OneToOne.class)
                        || f.isSynthetic()) {
                    continue;
                }
                jakarta.persistence.Column col = f.getAnnotation(jakarta.persistence.Column.class);
                if (col != null && !col.name().isBlank()) {
                    names.add(col.name());
                } else {
                    names.add(f.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase());
                }
            }
        }
        return names;
    }

    private Map<String, Set<String>> actualColumns() throws Exception {
        Map<String, Set<String>> byTable = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT table_name, column_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public'")) {
            while (rs.next()) {
                byTable.computeIfAbsent(rs.getString(1).toLowerCase(), k -> new LinkedHashSet<>())
                        .add(rs.getString(2).toLowerCase());
            }
        }
        return byTable;
    }

    /** {@code @Table(name=...)} 이 없으면 JPA 기본 규칙(클래스명 → snake_case)을 따른다. */
    private String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return entity.getSimpleName()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private List<Class<?>> entityClasses() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition def : scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)) {
            classes.add(Class.forName(def.getBeanClassName()));
        }
        assertThat(classes)
                .as("엔티티를 하나도 못 찾았다면 스캔 경로가 잘못된 것이다 — "
                        + "그대로 두면 이 테스트가 아무것도 검증하지 않는다")
                .isNotEmpty();
        return classes;
    }

    private Set<String> actualTableNames() throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1).toLowerCase());
            }
        }
        return names;
    }
}
