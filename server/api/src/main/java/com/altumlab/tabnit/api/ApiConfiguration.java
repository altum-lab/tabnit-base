package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityQuery;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.rules.ActionDecisions;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.link.EditionRecords;
import org.springframework.util.unit.DataSize;
import com.altumlab.tabnit.core.RelationQuery;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** api 가 쓰는 것들을 잇는다. 계산과 판단은 각 모듈이 하고, api 는 부르기만 한다. */
@Configuration(proxyBeanMethods = false)
class ApiConfiguration {

	/** 서버의 시계. 현재 시각은 api 층에서만 읽고, 엔진에는 날짜로 넘긴다 (D2 AD-23, AD-26). */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * "오늘"을 정하는 시간대. 설치 설정에서 받고, 적지 않으면 Asia/Seoul 이다.
	 * 서버 운영체제의 시간대에 기대지 않는다 (D2 AD-40).
	 */
	@Bean
	ZoneId installationZone(@Value("${tabnit.time-zone:Asia/Seoul}") String zoneId) {
		return ZoneId.of(zoneId);
	}

	@Bean
	RelationQuery relationQuery(DataSource dataSource) {
		return new RelationQuery(dataSource);
	}

	/** 판을 투입하고 그 변동을 조치까지 잇는다 (D2 AD-233). */
	@Bean
	EditionProcessing editionProcessing(DataSource dataSource, Clock clock,
			@Value("${spring.servlet.multipart.max-file-size:20MB}") DataSize maxFileSize) {
		// 파일 크기의 한도는 설정 하나로 정한다. 서버가 요청을 받을 때와 link 가 투입할 때 같은 값을 쓴다 (D7 5절).
		if (maxFileSize.toBytes() <= 0) {
			// "한도 없음"(-1)은 받지 않는다. 판의 원본을 저장소에 담으므로 한도가 반드시 있어야 한다.
			throw new IllegalStateException("spring.servlet.multipart.max-file-size 는 0 보다 커야 합니다: " + maxFileSize
					+ ". 올리는 파일 크기의 한도를 정해 다시 실행하세요 (예: 20MB).");
		}
		return new EditionProcessing(dataSource, clock, maxFileSize.toBytes());
	}

	@Bean
	EditionRecords editionRecords(DataSource dataSource) {
		return new EditionRecords(dataSource);
	}

	@Bean
	ActionRecords actionRecords(DataSource dataSource) {
		return new ActionRecords(dataSource);
	}

	@Bean
	ActionDecisions actionDecisions(DataSource dataSource) {
		return new ActionDecisions(dataSource);
	}

	@Bean
	ActionViews actionViews(EntityQuery entityQuery) {
		return new ActionViews(entityQuery);
	}

	@Bean
	PackDefinitions packDefinitions(DataSource dataSource) {
		return new PackDefinitions(dataSource);
	}

	@Bean
	EntityQuery entityQuery(DataSource dataSource) {
		return new EntityQuery(dataSource);
	}

}
