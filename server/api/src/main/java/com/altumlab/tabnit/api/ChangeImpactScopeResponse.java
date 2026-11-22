package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.StoredImpactScope;
import com.altumlab.tabnit.rules.StoredImpactScopePage;
import com.altumlab.tabnit.rules.StoredImpactTarget;
import com.altumlab.tabnit.rules.StoredPathStep;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 변동의 영향 범위의 응답 (docs/api/openapi.yaml 의 ChangeImpactScope, D7 6절).
 *
 * <p>목록의 순서와 수는 rules 가 준 그대로다. 여기서 바꾸거나 다시 세지 않는다.
 */
record ChangeImpactScopeResponse(String changeEventId, String packId, LocalDate asOfPrevious, LocalDate asOfEffective,
		int maxDepth, boolean truncated, Instant storedAt, int total, int offset, int limit, List<TypeCount> countsByType,
		List<DepthCount> countsByDepth, List<Target> targets) {

	record TypeCount(String entityType, int count) {
	}

	record DepthCount(int depth, int count) {
	}

	record Target(String entityId, String entityType, int depth, Path path) {
	}

	record Path(String startEntityId, LocalDate asOf, List<Step> steps) {
	}

	record Step(int stepNo, String relationId, String relationType, String sourceEntityId, String targetEntityId) {
	}

	/**
	 * @param found 영향 범위의 머리, 이 쪽의 영향 대상, 그리고 나누기 전의 수. 수는 rules 가 센 값이다
	 * @param page 답에 쓴 건너뛴 수와 한 번에 주는 수
	 */
	static ChangeImpactScopeResponse of(StoredImpactScopePage found, PageRequest page) {
		StoredImpactScope scope = found.scope();
		List<TypeCount> byType = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : found.countsByType().entrySet()) {
			byType.add(new TypeCount(entry.getKey(), entry.getValue()));
		}
		List<DepthCount> byDepth = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : found.countsByDepth().entrySet()) {
			byDepth.add(new DepthCount(entry.getKey(), entry.getValue()));
		}
		List<Target> targets = new ArrayList<>();
		for (StoredImpactTarget target : scope.targets()) {
			List<Step> steps = new ArrayList<>();
			for (StoredPathStep step : target.steps()) {
				steps.add(new Step(step.stepNo(), step.relationId(), step.relationType(), step.sourceEntityId(),
						step.targetEntityId()));
			}
			targets.add(new Target(target.entityId(), target.entityType(), target.depth(),
					new Path(target.pathStartEntityId(), target.pathAsOf(), steps)));
		}
		return new ChangeImpactScopeResponse(scope.changeEventId(), scope.packId(), scope.asOfPrevious(),
				scope.asOfEffective(), scope.maxDepth(), scope.truncated(), scope.storedAt(), found.total(), page.offset(), page.limit(), byType,
				byDepth, targets);
	}
}
