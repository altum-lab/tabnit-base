package com.altumlab.tabnit.api;

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

	/** 판을 투입하고 그 변동을 조치까지 잇는다 (D2 AD-233). 부르는 HTTP API 는 아직 없다. */
	@Bean
	EditionProcessing editionProcessing(DataSource dataSource, Clock clock) {
		return new EditionProcessing(dataSource, clock);
	}

}
