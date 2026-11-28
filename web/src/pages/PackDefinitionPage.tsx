import { useContext, useEffect, useRef, useState } from "react";
import { api, type PackDefinition, type PackSummary } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { SessionContext } from "../session";
import { formatInstant } from "../time";
import { PackLoader } from "./PackLoader";
import { VerificationRunner } from "./VerificationRunner";

// Pack 정의 화면 (카드 B-19, D7 8절 "Pack 의 정의", D2 AD-415). 적재된 Pack 의 정의를 본다.
// 서버의 listPacks 와 getPackDefinition (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않고 고치지 않는다.
// - 항목과 순서는 서버가 준 것이다. 다시 정렬하지 않는다. 수는 서버가 준 목록의 길이를 적은 것이다.
// - 정의를 고치는 단추가 없다. 정의를 고치려면 Pack 의 파일을 고쳐 다시 적재한다.
// - 관리자에게는 서버에 놓인 Pack 을 골라 적재하는 칸이 함께 보인다 (PackLoader, 카드 D-22). 담당자에게는 없다.
// - 제약은 Pack 의 파일의 글(SHACL) 그대로 보인다. 항목으로 풀지 않는다.

type Packs =
  | { kind: "loading" }
  | { kind: "loaded"; packs: PackSummary[] }
  | { kind: "failed"; message: string };

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; definition: PackDefinition }
  | { kind: "failed"; message: string };

const DIRECTIONS: Record<string, string> = {
  SOURCE_TO_TARGET: "출발에서 도착으로",
  TARGET_TO_SOURCE: "도착에서 출발로",
  BOTH: "양쪽",
  NONE: "퍼지지 않음",
};

const FORMATS: Record<string, string> = { STRING: "글자", INTEGER: "정수", DECIMAL: "소수", DATE: "날짜", BOOLEAN: "참·거짓" };

const CHANGE_KINDS: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

const SUBJECTS: Record<string, string> = { CHANGE_TARGET: "변동 대상", IMPACT_TARGET: "영향 대상", LINKED_TARGET: "이어진 대상" };

export function PackDefinitionPage() {
  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  const [packId, setPackId] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  const admin = useContext(SessionContext)?.role === "ADMIN";
  const mounted = useRef(true);

  // Pack 의 목록을 받는 차례. 늦게 온 옛 답이 새 답을 덮지 않게 한다 (적재를 잇달아 하면 목록을 잇달아 받는다).
  const listTurn = useRef(0);

  // 받은 목록을 화면에 올렸으면 참이다. 받지 못했거나, 그 사이에 새로 받기 시작했거나, 화면을 떠났으면 거짓이다.
  async function loadPacks(): Promise<boolean> {
    const turn = ++listTurn.current;
    try {
      const { data, error, response } = await api.GET("/api/v1/packs");
      if (!mounted.current || turn !== listTurn.current) {
        return false;
      }
      setPacks(data ? { kind: "loaded", packs: data.packs } : { kind: "failed", message: messageOf(error, response.status) });
      return Boolean(data);
    } catch {
      if (mounted.current && turn === listTurn.current) {
        setPacks({ kind: "failed", message: CONNECTION_FAILED });
      }
      return false;
    }
  }

  useEffect(() => {
    mounted.current = true;
    void loadPacks();
    return () => {
      mounted.current = false;
    };
  }, []);

  // 적재가 끝났다: 목록을 다시 받고 (처음 적재한 Pack 이 생긴다) 그 Pack 의 정의를 보인다.
  // 목록을 받지 못했거나 그 사이에 다른 것을 다시 받기 시작했으면 정의를 열지 않는다: 고르는 칸에 없는 Pack 의 정의가
  // 떠 있지 않게 한다.
  async function loaded(loadedPackId: string) {
    if (await loadPacks()) {
      await choose(loadedPackId);
    }
  }

  async function choose(next: string) {
    const turn = ++latest.current;
    setPackId(next);
    if (!next) {
      setView({ kind: "idle" });
      return;
    }
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/pack-definition", { params: { query: { packId: next } } });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", definition: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  return (
    <section aria-labelledby="pack-definition-title">
      <h1 id="pack-definition-title">Pack 정의</h1>
      <p>
        적재된 Pack 이 정한 것을 봅니다: 개체 유형, 속성, 관계 유형과 영향 방향, 입력 자료와 매핑, 규칙, 제약. 여기서는
        고칠 수 없습니다. 고치려면 Pack 의 파일을 고쳐 다시 적재합니다.
      </p>

      {admin && <PackLoader onLoaded={(loadedPackId) => void loaded(loadedPackId)} />}

      {packs.kind === "loading" && <p role="status">Pack 의 목록을 불러오는 중입니다…</p>}
      {packs.kind === "failed" && <p role="alert">{packs.message}</p>}
      {packs.kind === "loaded" && packs.packs.length === 0 && (
        <p>적재된 Pack 이 없습니다. 관리자가 Pack 을 적재한 뒤에 볼 수 있습니다.</p>
      )}
      {packs.kind === "loaded" && packs.packs.length > 0 && (
        <p>
          <label htmlFor="definition-pack">Pack</label>{" "}
          <select id="definition-pack" value={packId} onChange={(e) => void choose(e.target.value)}>
            <option value="">Pack 을 고르세요</option>
            {packs.packs.map((candidate) => (
              <option key={candidate.packId} value={candidate.packId}>
                {candidate.label} ({candidate.packId})
              </option>
            ))}
          </select>
        </p>
      )}

      {view.kind === "idle" && packs.kind === "loaded" && packs.packs.length > 0 && <p>Pack 을 고르면 그 정의가 나옵니다.</p>}
      {view.kind === "loading" && <p role="status">정의를 불러오는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && <Definition definition={view.definition} />}

      {/* 관리자에게만 보인다 (카드 E-23). 고른 Pack 의 검증 질문을 지금의 저장소에 묻는다. */}
      {admin && packId && <VerificationRunner packId={packId} />}
    </section>
  );
}

function Definition({ definition }: { definition: PackDefinition }) {
  const session = useContext(SessionContext);
  return (
    <div data-testid="definition" data-pack-id={definition.packId}>
      {/* 정의에는 조회 시점이 없다. 언제 적재한 것인지가 그 자리다: 다시 적재한 뒤의 것인지 알 수 있다. */}
      <p>
        적재한 때{" "}
        <time data-testid="definition-loaded-at" dateTime={definition.loadedAt}>
          {formatInstant(definition.loadedAt, session?.timeZone)}
        </time>{" "}
        <small>이때 적재한 정의입니다. Pack 을 다시 적재하면 바뀝니다.</small>
      </p>
      <p>
        <strong>{definition.label}</strong> <code>{definition.packId}</code> · 영향 범위의 최대 깊이의 기본값{" "}
        {definition.defaultMaxDepth} · 개체 유형 <span data-testid="count-entity-types">{definition.entityTypes.length}</span>
        , 속성 {definition.attributes.length}, 관계 유형{" "}
        <span data-testid="count-relation-types">{definition.relationTypes.length}</span>, 입력 자료{" "}
        {definition.inputSources.length}, 규칙 <span data-testid="count-rules">{definition.rules.length}</span>
      </p>

      <h2>개체 유형</h2>
      <table aria-label="개체 유형">
        <thead>
          <tr>
            <th scope="col">이름표</th>
            <th scope="col">이름</th>
            <th scope="col">상위 유형</th>
            <th scope="col">표시 이름의 속성</th>
          </tr>
        </thead>
        <tbody>
          {definition.entityTypes.map((type) => (
            <tr key={type.name} data-name={type.name}>
              <td>{type.label}</td>
              <td>
                <code>{type.name}</code>
              </td>
              <td>{type.parents.join(", ")}</td>
              <td>
                {/* 상위 유형에 있는지는 답이 알려 주지 않는다: 받는다고 단정하지 않는다. */}
                {type.displayNameAttribute ??
                  (type.parents.length > 0 ? <small>이 유형에는 적지 않았습니다. 상위 유형에 있으면 그것을 씁니다.</small> : "")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>속성</h2>
      <table aria-label="속성">
        <thead>
          <tr>
            <th scope="col">이름표</th>
            <th scope="col">이름</th>
            <th scope="col">개체 유형</th>
            <th scope="col">값의 형식</th>
          </tr>
        </thead>
        <tbody>
          {definition.attributes.map((attribute) => (
            <tr key={attribute.name} data-name={attribute.name}>
              <td>{attribute.label}</td>
              <td>
                <code>{attribute.name}</code>
              </td>
              <td>{attribute.entityType}</td>
              <td>{FORMATS[attribute.valueFormat] ?? attribute.valueFormat}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>관계 유형</h2>
      <table aria-label="관계 유형">
        <thead>
          <tr>
            <th scope="col">이름표</th>
            <th scope="col">이름</th>
            <th scope="col">출발 유형</th>
            <th scope="col">도착 유형</th>
            <th scope="col">영향 방향</th>
          </tr>
        </thead>
        <tbody>
          {definition.relationTypes.map((type) => (
            <tr key={type.name} data-name={type.name}>
              <td>{type.label}</td>
              <td>
                <code>{type.name}</code>
              </td>
              <td>{type.sourceType}</td>
              <td>{type.targetType}</td>
              <td>{DIRECTIONS[type.impactDirection] ?? type.impactDirection}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>입력 자료와 매핑</h2>
      <table aria-label="입력 자료와 매핑">
        <thead>
          <tr>
            <th scope="col">입력 자료</th>
            <th scope="col">파일</th>
            <th scope="col">있어야 하는 열</th>
            <th scope="col">항목의 키 열</th>
            <th scope="col">행이 만드는 개체 (속성 ← 열)</th>
            <th scope="col">행이 만드는 관계</th>
          </tr>
        </thead>
        <tbody>
          {definition.inputSources.map((source) => (
            <tr key={source.inputSourceId} data-input-source-id={source.inputSourceId}>
              <td>
                {source.label} <code>{source.inputSourceId}</code>
              </td>
              <td>
                {source.fileFormat}
                {source.sheetName !== null && <small> 시트 {source.sheetName}</small>}
              </td>
              <td>{source.requiredColumns.join(", ")}</td>
              <td>{source.itemKeyColumns.join(", ")}</td>
              <td>
                {source.entities.map((entity, index) => (
                  <div key={index}>
                    {entity.entityType} <small>(키 ← {entity.keyColumn})</small>
                    {entity.attributes.length > 0 && (
                      <small>
                        : {entity.attributes.map((attribute) => `${attribute.attributeName} ← ${attribute.column}`).join(", ")}
                      </small>
                    )}
                  </div>
                ))}
              </td>
              <td>
                {source.relations.map((relation, index) => (
                  <div key={index}>
                    {relation.source.entityType} —{relation.relationType}→ {relation.target.entityType}
                  </div>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>규칙</h2>
      <table aria-label="규칙">
        <thead>
          <tr>
            <th scope="col">번호</th>
            <th scope="col">이름</th>
            <th scope="col">우선순위</th>
            <th scope="col">걸리는 변동</th>
            <th scope="col">조치의 대상</th>
            <th scope="col">조건</th>
            <th scope="col">할 일</th>
            <th scope="col">기한</th>
          </tr>
        </thead>
        <tbody>
          {definition.rules.map((rule) => (
            <tr key={rule.ruleNo} data-rule-no={rule.ruleNo}>
              <td>{rule.ruleNo}</td>
              <td>{rule.label}</td>
              <td>{rule.priority}</td>
              <td>
                {rule.changeKinds.map((kind) => CHANGE_KINDS[kind] ?? kind).join(", ")}
                {rule.inputSources.length > 0 && <small> 입력 자료: {rule.inputSources.join(", ")}</small>}
                {rule.changedAttributes.length > 0 && <small> 바뀐 속성: {rule.changedAttributes.join(", ")}</small>}
                {rule.changedRelationTypes.length > 0 && <small> 바뀐 관계 유형: {rule.changedRelationTypes.join(", ")}</small>}
              </td>
              <td>
                {SUBJECTS[rule.appliesTo] ?? rule.appliesTo} <small>({rule.targetType})</small>
              </td>
              <td>
                {rule.conditions.map((condition, index) => (
                  <div key={index}>
                    <small>
                      {SUBJECTS[condition.subject] ?? condition.subject}
                      {condition.linkedBy !== null && ` (${condition.linkedBy} 로 이어진)`}
                      {condition.typeIs !== null && ` 의 유형이 ${condition.typeIs}`}
                      {condition.attributeName !== null &&
                        ` 의 ${condition.attributeName} ${condition.operator ?? ""} ${
                          // 무엇과 견주는가: 판의 적용일, 다른 속성, 적어 둔 값.
                          condition.compareTo === "EFFECTIVE_DATE"
                            ? "판의 적용일"
                            : condition.compareAttributeName !== null
                              ? `${SUBJECTS[condition.compareSubject ?? ""] ?? condition.compareSubject ?? ""} 의 ${condition.compareAttributeName}`
                              : condition.values.join(", ")
                        }`}
                    </small>
                  </div>
                ))}
              </td>
              <td>{rule.actionText}</td>
              <td>{rule.dueInDays}일</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>제약</h2>
      <p>
        <small>Pack 의 제약 파일의 글(SHACL) 그대로입니다. 어긴 곳은 변동 감지 화면의 "제약의 위반"에서 봅니다.</small>
      </p>
      {definition.constraints.trim() === "" ? (
        <p>
          이 Pack 에는 제약이 없습니다.<span data-testid="constraints"></span>
        </p>
      ) : (
        <pre data-testid="constraints" style={{ maxHeight: "24rem", overflow: "auto" }}>
          {definition.constraints}
        </pre>
      )}
    </div>
  );
}
