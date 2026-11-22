package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-06 수용 기준: 관계 지도 화면이 쓰는 형식으로 돌려준다 (규격의 getRelationMap, D7 4절 "관계 지도", D2 AD-352).
 *
 * <p>개체 하나를 가운데 두고, 조회 시점에 유효한 관계로 이어진 이웃을 노드와 간선으로 돌려준다. 방향과 무관하게
 * 따라가고, 깊이는 기본 1 · 최대 3, 노드는 200개에서 자르고 알린다.
 *
 * <p>시험 자료는 지어낸 값이다. 실제 PostgreSQL 에 core 의 저장 API 로 넣는다.
 */
@Import({ TestcontainersConfiguration.class, RelationMapApiTests.FixedClock.class })
@SpringBootTest
class RelationMapApiTests {

	private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

	private static final Provenance SRC_1 = new Provenance("src", 1);

	/** 서버의 시계를 고정한다. 이 순간은 협정 세계시로는 2026-02-28 이고 Asia/Seoul 로는 2026-03-01 이다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClock {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"), ZoneOffset.UTC);
		}

	}

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	MockMvc mvc;

	RelationStore store;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		store = new RelationStore(dataSource);
	}

	@Test
	void 가운데_개체와_이웃을_노드로_그_사이의_관계를_간선으로_돌려준다() throws Exception {
		String pack = "map-api";
		EntityRef center = entity(pack, "center");
		EntityRef up = entity(pack, "up");
		EntityRef down = entity(pack, "down");
		String in = relation(up, center, "R", "2026-01-01", "2026-06-01");
		String out = relation(center, down, "R", "2026-01-01", null);

		mvc.perform(get("/api/v1/relation-map").param("entityId", center.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").value("2026-03-01"))
			.andExpect(jsonPath("$.centerEntityId").value(center.id()))
			.andExpect(jsonPath("$.depth").value(1))
			.andExpect(jsonPath("$.maxNodes").value(200))
			.andExpect(jsonPath("$.totalNodes").value(3))
			.andExpect(jsonPath("$.truncated").value(false))
			// 노드: 깊이, 유형, 식별자 순. 가운데가 맨 앞.
			.andExpect(jsonPath("$.nodes[*].entityId", contains(center.id(), down.id(), up.id())))
			.andExpect(jsonPath("$.nodes[*].depth", contains(0, 1, 1)))
			// 개체는 유형, 키, 표시 이름과 함께 준다 (정의가 적재되지 않은 Pack: 이름표는 유형의 이름, 표시 이름은 없다).
			.andExpect(jsonPath("$.nodes[1].entity.entityId").value(down.id()))
			.andExpect(jsonPath("$.nodes[1].entity.entityType").value("T"))
			.andExpect(jsonPath("$.nodes[1].entity.entityTypeLabel").value("T"))
			.andExpect(jsonPath("$.nodes[1].entity.entityKey").value("down"))
			.andExpect(jsonPath("$.nodes[1].entity.displayName").value(nullValue()))
			// 간선: 유형, 출발, 도착, 식별자 순. 관계에 적힌 방향 그대로다.
			.andExpect(jsonPath("$.edges[*].id", contains(out, in)))
			.andExpect(jsonPath("$.edges[0].relationType").value("R"))
			.andExpect(jsonPath("$.edges[0].sourceEntityId").value(center.id()))
			.andExpect(jsonPath("$.edges[0].targetEntityId").value(down.id()))
			.andExpect(jsonPath("$.edges[0].validFrom").value("2026-01-01"))
			.andExpect(jsonPath("$.edges[0]").value(org.hamcrest.Matchers.hasKey("validTo")))
			.andExpect(jsonPath("$.edges[0].validTo").value(nullValue()))
			.andExpect(jsonPath("$.edges[1].validTo").value("2026-06-01"))
			.andExpect(jsonPath("$.edges[0].provenance.inputSourceId").value("src"))
			.andExpect(jsonPath("$.edges[0].provenance.editionNo").value(1))
			.andExpect(jsonPath("$.edges[0].recordedAt").value("2026-01-01T00:00:00Z"));
	}

	@Test
	void 깊이를_주면_그만큼_따라가고_조회_시점에_유효하지_않은_관계는_따라가지_않는다() throws Exception {
		String pack = "map-api-depth";
		EntityRef a = entity(pack, "a");
		EntityRef b = entity(pack, "b");
		EntityRef c = entity(pack, "c");
		EntityRef d = entity(pack, "d");
		relation(a, b, "R", "2026-01-01", null);
		relation(b, c, "R", "2026-01-01", null);
		// 조회 시점에는 끝난 관계.
		relation(c, d, "R", "2026-01-01", "2026-03-01");

		mvc.perform(get("/api/v1/relation-map").param("entityId", a.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodes[*].entityId", contains(a.id(), b.id())));
		mvc.perform(get("/api/v1/relation-map").param("entityId", a.id()).param("asOf", "2026-03-01").param("depth", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.depth").value(3))
			.andExpect(jsonPath("$.nodes[*].entityId", contains(a.id(), b.id(), c.id())))
			.andExpect(jsonPath("$.nodes[*].depth", contains(0, 1, 2)))
			.andExpect(jsonPath("$.edges", hasSize(2)));
		// 하루 전에는 끝난 관계도 유효하다.
		mvc.perform(get("/api/v1/relation-map").param("entityId", a.id()).param("asOf", "2026-02-28").param("depth", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodes", hasSize(4)))
			.andExpect(jsonPath("$.edges", hasSize(3)));
	}

	@Test
	void 노드가_이백_개를_넘으면_가까운_것부터_이백_개만_담고_잘렸다고_알린다() throws Exception {
		String pack = "map-api-cut";
		EntityRef center = entity(pack, "center");
		for (int i = 0; i < 250; i++) {
			relation(center, entity(pack, String.format("n-%03d", i)), "R", "2026-01-01", null);
		}

		mvc.perform(get("/api/v1/relation-map").param("entityId", center.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalNodes").value(251))
			.andExpect(jsonPath("$.truncated").value(true))
			.andExpect(jsonPath("$.nodes", hasSize(200)))
			.andExpect(jsonPath("$.nodes[0].entityId").value(center.id()))
			.andExpect(jsonPath("$.nodes[199].entityId").value(pack + "|T|n-198"))
			// 간선은 담긴 노드끼리의 것만.
			.andExpect(jsonPath("$.edges", hasSize(199)));
	}

	@Test
	void 관계가_없는_개체는_자신만_담고_조회_시점이_없으면_오늘로_답한다() throws Exception {
		EntityRef alone = entity("map-api-alone", "a");

		mvc.perform(get("/api/v1/relation-map").param("entityId", alone.id()))
			.andExpect(status().isOk())
			// 시계는 Asia/Seoul 로 2026-03-01 이다.
			.andExpect(jsonPath("$.asOf").value("2026-03-01"))
			.andExpect(jsonPath("$.nodes[*].entityId", contains(alone.id())))
			.andExpect(jsonPath("$.edges", hasSize(0)))
			.andExpect(jsonPath("$.totalNodes").value(1))
			.andExpect(jsonPath("$.truncated").value(false));
	}

	@Test
	void 틀린_요청은_어느_항목이_틀렸는지_알린다() throws Exception {
		EntityRef a = entity("map-api-wrong", "a");

		mvc.perform(get("/api/v1/relation-map").param("asOf", "2026-03-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_ENTITY_ID"))
			.andExpect(jsonPath("$.field").value("entityId"));
		mvc.perform(get("/api/v1/relation-map").param("entityId", a.id()).param("asOf", "2026/03/01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AS_OF"))
			.andExpect(jsonPath("$.field").value("asOf"));
		for (String depth : new String[] { "0", "4", "-1", "abc", "", "01", "1.0", " 1", "２" }) {
			mvc.perform(get("/api/v1/relation-map").param("entityId", a.id()).param("asOf", "2026-03-01").param("depth", depth))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DEPTH"))
				.andExpect(jsonPath("$.field").value("depth"));
		}
		mvc.perform(get("/api/v1/relation-map").param("entityId", "map-api-wrong|T|없는 개체").param("asOf", "2026-03-01"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ENTITY_NOT_FOUND"));
	}

	private EntityRef entity(String pack, String key) {
		EntityRef ref = new EntityRef(pack, "T", key);
		store.saveEntity(ref, RECORDED_AT);
		return ref;
	}

	private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
		ValidityPeriod period = new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
		return store.saveRelation(new NewRelation(source, target, type, period, SRC_1), RECORDED_AT);
	}

}
