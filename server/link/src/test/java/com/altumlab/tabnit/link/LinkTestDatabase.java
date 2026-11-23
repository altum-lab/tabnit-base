package com.altumlab.tabnit.link;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * link 시험이 함께 쓰는 실제 PostgreSQL. 빈 컨테이너에 스키마 변경 파일을 처음부터 적용한다.
 *
 * <p>변경 파일은 api 모듈 한 곳에 있다 (D2 AD-46). link 는 api 에 의존할 수 없으므로 시험에서만 파일 경로로 읽는다.
 * 컨테이너는 시험 전체에서 하나만 띄운다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 * Pack 식별자를 바꿀 수 없는 시험(저장소에 들어 있는 Pack 을 넣는 시험)은 {@link #fresh()} 로 빈 데이터베이스를 따로 받는다.
 */
final class LinkTestDatabase {

    private static final String MIGRATIONS = "filesystem:../api/src/main/resources/db/migration";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18"));

    private static final DataSource DATA_SOURCE = start();

    private static final java.util.concurrent.atomic.AtomicInteger FRESH = new java.util.concurrent.atomic.AtomicInteger();

    private LinkTestDatabase() {
    }

    static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** 같은 컨테이너에 빈 데이터베이스를 하나 더 만들어 스키마 변경 파일을 처음부터 적용한다. 다른 시험이 넣은 것이 없다. */
    static DataSource fresh() {
        String name = "fresh_" + FRESH.incrementAndGet();
        new org.springframework.jdbc.core.JdbcTemplate(DATA_SOURCE).execute("create database " + name);
        String url = CONTAINER.getJdbcUrl().replace("/" + CONTAINER.getDatabaseName(), "/" + name);
        return migrated(url);
    }

    private static DataSource start() {
        CONTAINER.start();
        return migrated(CONTAINER.getJdbcUrl());
    }

    private static DataSource migrated(String url) {
        Flyway.configure()
                .dataSource(url, CONTAINER.getUsername(), CONTAINER.getPassword())
                .locations(MIGRATIONS)
                .load()
                .migrate();
        return new DriverManagerDataSource(url, CONTAINER.getUsername(), CONTAINER.getPassword());
    }
}
