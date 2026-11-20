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
 */
final class LinkTestDatabase {

    private static final String MIGRATIONS = "filesystem:../api/src/main/resources/db/migration";

    private static final DataSource DATA_SOURCE = start();

    private LinkTestDatabase() {
    }

    static DataSource dataSource() {
        return DATA_SOURCE;
    }

    private static DataSource start() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations(MIGRATIONS)
                .load()
                .migrate();
        return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
