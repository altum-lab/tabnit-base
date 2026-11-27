package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-17: 걸러 낼 유형을 하위 유형까지 푸는 일 (D7 4절 "개체 찾기", D2 AD-411 의 (라)). Pack 과 무관한 일반 시험이다.
 *
 * <p>유형의 이름은 지어낸 것이다. 저장소를 쓰지 않는다.
 */
class EntityTypeExpansionTest {

	/** 위아래: Root ← Mid ← Leaf, Root ← Side, Other ← Both, Mid ← Both (상위 유형이 둘), Alone. */
	private static final PackDefinition PACK = new PackDefinition("expand", "지어낸 Pack", 3,
			Instant.parse("2026-01-01T00:00:00Z"),
			List.of(type("Alone"), type("Both", "Mid", "Other"), type("Leaf", "Mid"), type("Mid", "Root"), type("Other"),
					type("Root"), type("Side", "Root")),
			List.of(), List.of(), List.of(), List.of());

	@Test
	void 유형이_없으면_비어_있다_모든_유형이라는_뜻이다() {
		assertThat(EntitiesController.expand(PACK, List.of())).isEmpty();
	}

	@Test
	void 하위_유형이_없는_유형은_그것_하나다() {
		assertThat(EntitiesController.expand(PACK, List.of("Leaf"))).containsExactly("Leaf");
		assertThat(EntitiesController.expand(PACK, List.of("Alone"))).containsExactly("Alone");
	}

	@Test
	void 상위_유형은_여러_단계_아래의_유형까지_푼다() {
		assertThat(EntitiesController.expand(PACK, List.of("Root"))).containsExactly("Both", "Leaf", "Mid", "Root", "Side");
		assertThat(EntitiesController.expand(PACK, List.of("Mid"))).containsExactly("Both", "Leaf", "Mid");
	}

	@Test
	void 상위_유형이_둘인_유형은_어느_쪽으로_물어도_나온다() {
		assertThat(EntitiesController.expand(PACK, List.of("Other"))).containsExactly("Both", "Other");
	}

	@Test
	void 여러_유형을_주면_합친_것이고_차례와_겹침은_닿지_않는다() {
		assertThat(EntitiesController.expand(PACK, List.of("Side", "Other", "Side")))
			.containsExactly("Both", "Other", "Side");
		assertThat(EntitiesController.expand(PACK, List.of("Other", "Side")))
			.isEqualTo(EntitiesController.expand(PACK, List.of("Side", "Other", "Side")));
	}

	@Test
	void 없는_유형과_빈_이름은_거부한다() {
		assertThatThrownBy(() -> EntitiesController.expand(PACK, List.of("Root", "없는유형")))
			.isInstanceOf(ApiProblemException.class);
		assertThatThrownBy(() -> EntitiesController.expand(PACK, List.of(""))).isInstanceOf(ApiProblemException.class);
		// 이름은 글자 그대로 견준다.
		assertThatThrownBy(() -> EntitiesController.expand(PACK, List.of("root"))).isInstanceOf(ApiProblemException.class);
	}

	private static EntityTypeDefinition type(String name, String... parents) {
		return new EntityTypeDefinition(name, name, List.of(parents), null);
	}
}
