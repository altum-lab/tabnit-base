import { useCallback, useContext, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import {
  type Action,
  type ActionDecision,
  type ActionDetail,
  api,
  type ChangeImpactScope,
  type ImpactTarget,
  type PackSummary,
} from "../api/client";
import { CONNECTION_FAILED, isProblem, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";
import { Pager } from "./Pager";
import { SessionContext } from "../session";
import { formatInstant } from "../time";

// 조치 화면. Pack 의 조치를 근거와 함께 보고, 그 자리에서 확정하거나 보류한다.
// 서버의 listActions, getAction, getChangeImpactScope, decideAction (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 무엇이 조치이고 어떤 순서인지, 몇 건인지, 기한과 우선순위는 서버가 정한다. 화면은 다시 세거나 정렬하지 않는다.
// - 확정하거나 보류할 수 있는지는 서버가 정한다. 화면은 지금의 상태에 맞는 단추만 보여 주고, 서버가 거부하면
//   그 문구를 보여 준다. 누가 했는지와 언제 했는지는 서버가 채운다.
// - 고른 Pack, 상태, 변동은 주소에 둔다. 새로 고치거나 주소를 건네도 같은 목록이 열린다.

const STATUS_LABEL: Record<string, string> = { PENDING: "대기", CONFIRMED: "확정", ON_HOLD: "보류" };

// 주소의 status 값. ALL 은 가리지 않는다는 뜻이고 서버에는 보내지 않는다.
const STATUS_CHOICES = [
  { value: "PENDING", label: "대기" },
  { value: "ON_HOLD", label: "보류" },
  { value: "CONFIRMED", label: "확정" },
  { value: "ALL", label: "전체" },
] as const;

type StatusChoice = (typeof STATUS_CHOICES)[number]["value"];

type Packs =
  | { kind: "loading" }
  | { kind: "loaded"; packs: PackSummary[] }
  | { kind: "failed"; message: string };

type List =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; total: number; offset: number; limit: number; actions: Action[] }
  | { kind: "failed"; message: string };

function statusChoice(value: string | null): StatusChoice {
  return STATUS_CHOICES.some((choice) => choice.value === value) ? (value as StatusChoice) : "PENDING";
}

export function ActionsPage() {
  const [params, setParams] = useSearchParams();
  const packId = params.get("packId") ?? "";
  const status = statusChoice(params.get("status"));
  const changeEventId = params.get("changeEventId");
  // 건너뛸 수. 주소에 둔다: 새로 고치거나 주소를 건네도 같은 쪽이 열린다. 틀린 값은 서버가 알린다.
  const offset = params.get("offset");

  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  const [list, setList] = useState<List>({ kind: "idle" });
  // 펼쳐 둔 조치. 목록을 다시 받아도 그대로 둔다.
  const [opened, setOpened] = useState<string[]>([]);
  const latestList = useRef(0);
  // 목록을 받은 뒤에 이 화면에서 상태가 바뀐 조치가 있는가.
  const [changedSinceLoad, setChangedSinceLoad] = useState(false);
  // 지금 보여 주는 목록. 조치 하나가 바뀌었을 때 앞의 상태와 견주는 데 쓴다.
  const shownList = useRef<List>(list);
  shownList.current = list;

  const loadPacks = useCallback(async () => {
    setPacks({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/packs");
      setPacks(data ? { kind: "loaded", packs: data.packs } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      setPacks({ kind: "failed", message: CONNECTION_FAILED });
    }
  }, []);

  useEffect(() => {
    void loadPacks();
  }, [loadPacks]);

  // 목록을 받아 온다.
  const loadList = useCallback(
    async () => {
      // 가장 나중에 보낸 요청의 답만 쓴다. Pack 을 비웠을 때도 번호를 올려, 앞 Pack 의 늦은 답이 덮지 않게 한다.
      const request = ++latestList.current;
      setChangedSinceLoad(false);
      if (!packId) {
        setList({ kind: "idle" });
        return;
      }
      setList({ kind: "loading" });
      try {
        const { data, error, response } = await api.GET("/api/v1/actions", {
          params: {
            query: {
              packId,
              ...(status === "ALL" ? {} : { status }),
              ...(changeEventId ? { changeEventId } : {}),
              // 글자 그대로 보낸다. 수인지와 범위는 서버가 확인해 알린다.
              ...(offset ? { offset: offset as unknown as number } : {}),
            },
          },
        });
        if (request !== latestList.current) {
          return;
        }
        setList(
          data
            ? { kind: "loaded", total: data.total, offset: data.offset, limit: data.limit, actions: data.actions }
            : { kind: "failed", message: messageOf(error, response.status) },
        );
      } catch {
        if (request === latestList.current) {
          setList({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    },
    [packId, status, changeEventId, offset],
  );

  useEffect(() => {
    void loadList();
  }, [loadList]);

  // 조치 하나가 바뀌었다 (확정, 보류, 또는 다른 사람이 바꾼 것을 다시 읽음). 목록을 통째로 다시 받지 않고 그 줄만
  // 서버가 준 값으로 고친다: 방금 한 결과가 목록에서 사라지지 않는다. 목록의 수와 거르는 상태는 받았을 때의 것이라,
  // 다시 받으라고 알린다.
  const replaceAction = useCallback((changed: Action) => {
    const shown = shownList.current;
    const before = shown.kind === "loaded" ? shown.actions.find((action) => action.id === changed.id) : undefined;
    setList((current) =>
      current.kind === "loaded"
        ? { ...current, actions: current.actions.map((action) => (action.id === changed.id ? changed : action)) }
        : current,
    );
    // 펼쳐서 읽기만 한 것은 바뀐 것이 아니다. 상태가 달라졌을 때만 알린다.
    if (before && before.status !== changed.status) {
      setChangedSinceLoad(true);
    }
  }, []);

  function move(nextOffset: number) {
    const updated = new URLSearchParams(params);
    if (nextOffset > 0) {
      updated.set("offset", String(nextOffset));
    } else {
      updated.delete("offset");
    }
    setOpened([]);
    setParams(updated);
  }

  function choose(next: { packId?: string; status?: StatusChoice; changeEventId?: null }) {
    const updated = new URLSearchParams(params);
    // 거르는 것을 바꾸면 처음부터 본다.
    updated.delete("offset");
    if (next.packId !== undefined) {
      updated.set("packId", next.packId);
      // Pack 을 바꾸면 앞 Pack 의 변동으로 거르던 것은 뜻이 없다.
      updated.delete("changeEventId");
    }
    if (next.status !== undefined) {
      updated.set("status", next.status);
    }
    if (next.changeEventId === null) {
      updated.delete("changeEventId");
    }
    setOpened([]);
    setParams(updated);
  }

  return (
    <section aria-labelledby="actions-title">
      <h1 id="actions-title">조치</h1>
      <p>변동에서 나온 해야 할 일입니다. 근거를 확인하고 확정하거나 보류합니다.</p>

      {packs.kind === "loading" && <p role="status">Pack 의 목록을 불러오는 중입니다…</p>}

      {packs.kind === "failed" && (
        <p role="alert">
          {packs.message}{" "}
          <button type="button" onClick={() => void loadPacks()}>
            다시 불러오기
          </button>
        </p>
      )}

      {packs.kind === "loaded" && packs.packs.length === 0 && (
        <p role="status">적재된 Pack 이 없습니다. Pack 을 먼저 적재한 뒤 다시 여세요.</p>
      )}

      {packs.kind === "loaded" && packs.packs.length > 0 && (
        <form onSubmit={(event) => event.preventDefault()}>
          <p>
            <label htmlFor="actions-pack">Pack</label>{" "}
            <select id="actions-pack" value={packId} onChange={(e) => choose({ packId: e.target.value })}>
              <option value="">고르세요</option>
              {packs.packs.map((candidate) => (
                <option key={candidate.packId} value={candidate.packId}>
                  {candidate.label} ({candidate.packId})
                </option>
              ))}
            </select>{" "}
            <label htmlFor="actions-status">상태</label>{" "}
            <select
              id="actions-status"
              value={status}
              onChange={(e) => choose({ status: statusChoice(e.target.value) })}
              disabled={!packId}
            >
              {STATUS_CHOICES.map((choice) => (
                <option key={choice.value} value={choice.value}>
                  {choice.label}
                </option>
              ))}
            </select>
          </p>
        </form>
      )}

      {changeEventId && (
        <p data-testid="change-filter">
          변동 하나의 조치만 보고 있습니다 (변동 이벤트 {changeEventId}).{" "}
          <button type="button" onClick={() => choose({ changeEventId: null })}>
            이 Pack 의 조치 모두 보기
          </button>
        </p>
      )}

      {list.kind === "loading" && <p role="status">조치를 불러오는 중입니다…</p>}

      {list.kind === "failed" && (
        <p role="alert">
          {list.message}{" "}
          <button type="button" onClick={() => void loadList()}>
            다시 불러오기
          </button>
        </p>
      )}

      {list.kind === "loaded" && (
        <>
          <p>
            조치 <span data-testid="action-total">{list.total}건</span>
          </p>
          {changedSinceLoad && (
            <p data-testid="list-outdated">
              목록을 받은 뒤에 상태가 바뀐 조치가 있습니다. 위의 수와 거르는 상태는 받았을 때의 것입니다.{" "}
              <button type="button" onClick={() => void loadList()}>
                목록 다시 받기
              </button>
            </p>
          )}
          {list.total > 0 && (
            <Pager total={list.total} offset={list.offset} limit={list.limit} shown={list.actions.length} onMove={move} />
          )}
          {list.total === 0 ? (
            <p role="status">해당하는 조치가 없습니다.</p>
          ) : list.actions.length === 0 ? (
            <p role="status">이 자리에는 조치가 없습니다. 이전으로 돌아가세요.</p>
          ) : (
            <table>
              <caption>조치 목록</caption>
              <thead>
                <tr>
                  <th scope="col">기한</th>
                  <th scope="col">우선순위</th>
                  <th scope="col">해야 할 일</th>
                  <th scope="col">대상</th>
                  <th scope="col">근거 조항</th>
                  <th scope="col">근거 조항의 판</th>
                  <th scope="col">상태</th>
                  <th scope="col">근거와 이력</th>
                </tr>
              </thead>
              <tbody>
                {list.actions.map((action) => {
                  const isOpen = opened.includes(action.id);
                  return [
                    <tr key={action.id} data-action-id={action.id}>
                      <td>{action.dueDate}</td>
                      <td>{action.priority}</td>
                      <td>
                        {action.actionText}
                        <br />
                        <small>
                          규칙 {action.ruleNo} {action.ruleLabel}
                        </small>
                      </td>
                      <td>
                        <EntityName entity={action.target} />
                      </td>
                      <td>
                        <EntityName entity={action.basis.entity} />
                      </td>
                      <td>
                        {action.basis.inputSourceId} {action.basis.editionNo}판
                        <br />
                        <small>적용일 {action.basis.effectiveDate}</small>
                      </td>
                      <td data-testid="status">{STATUS_LABEL[action.status] ?? action.status}</td>
                      <td>
                        <button
                          type="button"
                          aria-expanded={isOpen}
                          onClick={() =>
                            setOpened((current) =>
                              isOpen ? current.filter((id) => id !== action.id) : [...current, action.id],
                            )
                          }
                        >
                          {isOpen ? "근거와 이력 닫기" : "근거와 이력 보기"}
                        </button>
                      </td>
                    </tr>,
                    isOpen && (
                      <tr key={`${action.id}#detail`}>
                        <td colSpan={8} data-detail-of={action.id}>
                          <Detail action={action} onAction={replaceAction} />
                        </td>
                      </tr>
                    ),
                  ];
                })}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}

type Loaded<T> = { kind: "loading" } | { kind: "loaded"; value: T } | { kind: "failed"; message: string };

// 조치 하나의 근거 경로와 이력, 그리고 확정과 보류.
//
// 조치의 상태는 목록의 그 줄 하나에만 둔다 (action). 여기서 서버가 새 조치를 주면 onAction 으로 올려 그 줄을 고친다:
// 목록의 상태 칸과 여기의 단추가 서로 다른 상태를 보지 않는다.
function Detail({ action, onAction }: { action: Action; onAction: (changed: Action) => void }) {
  const [history, setHistory] = useState<Loaded<ActionDecision[]>>({ kind: "loading" });
  const [scope, setScope] = useState<Loaded<ChangeImpactScope> | null>(null);
  const [note, setNote] = useState("");
  const [deciding, setDeciding] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  // 이력을 받는 요청과 확정·보류의 답 가운데 가장 나중 것만 쓴다. 늦게 온 옛 이력이 새 이력을 덮지 않게 한다.
  const latestHistory = useRef(0);

  const accept = useCallback(
    (detail: ActionDetail) => {
      setHistory({ kind: "loaded", value: detail.history });
      onAction(detail.action);
    },
    [onAction],
  );

  const loadDetail = useCallback(async () => {
    const request = ++latestHistory.current;
    try {
      const { data, error, response } = await api.GET("/api/v1/action", { params: { query: { actionId: action.id } } });
      if (request !== latestHistory.current) {
        return;
      }
      if (data) {
        accept(data);
      } else {
        setHistory({ kind: "failed", message: messageOf(error, response.status) });
      }
    } catch {
      if (request === latestHistory.current) {
        setHistory({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }, [action.id, accept]);

  const loadScope = useCallback(async () => {
    setScope({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/impact-scopes", {
        // 이 조치의 대상 하나만 묻는다. 영향 대상은 나눠 오므로 통째로 받아 찾지 않는다 (D2 AD-347).
        params: { query: { changeEventId: action.changeEventId, entityId: action.target.entityId } },
      });
      setScope(data ? { kind: "loaded", value: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      setScope({ kind: "failed", message: CONNECTION_FAILED });
    }
  }, [action.changeEventId, action.target.entityId]);

  // 펼쳤을 때, 그리고 목록이 이 조치의 상태를 다르게 알려 왔을 때 이력을 다시 받는다.
  useEffect(() => {
    void loadDetail();
  }, [loadDetail, action.status]);

  useEffect(() => {
    // 근거 경로는 대상이 영향 대상인 조치에만 있다. 그 변동의 영향 범위에서 대상으로 찾는다.
    if (action.appliesTo === "IMPACT_TARGET") {
      void loadScope();
    }
  }, [action.appliesTo, loadScope]);

  async function decide(decision: "CONFIRM" | "HOLD") {
    setDeciding(true);
    setRefused(null);
    try {
      const { data, error, response } = await api.POST("/api/v1/action-decisions", {
        body: { actionId: action.id, decision, note: note === "" ? null : note },
      });
      if (data) {
        // 방금 바꾼 결과가 가장 새것이다. 그 전에 보낸 이력 요청의 답이 늦게 와도 쓰지 않는다.
        // 실패했을 때는 번호를 올리지 않는다: 받고 있던 이력을 버리면 이력이 끝내 오지 않는다.
        ++latestHistory.current;
        accept(data);
        setNote("");
      } else {
        setRefused(messageOf(error, response.status));
        if (response.status === 409 && isProblem(error)) {
          // 그 사이에 다른 사람이 바꿨다 (또는 앞서 보낸 것이 이미 됐다). 지금의 상태와 이력으로 고친다.
          void loadDetail();
        }
      }
    } catch {
      setRefused(CONNECTION_FAILED);
    } finally {
      setDeciding(false);
    }
  }

  // 지금의 상태에 맞는 단추만 보여 준다 (대기: 확정과 보류, 보류: 확정, 확정: 없음. D7 7절).
  // 되는지 안 되는지는 서버가 정한다: 서버가 거부하면 그 문구를 보여 준다.
  const status = action.status;

  return (
    <div>
      <section aria-label="근거 경로">
        <h2>근거 경로</h2>
        {action.appliesTo === "CHANGE_TARGET" ? (
          <p>
            대상이 바뀐 개체 자신이라 근거 경로가 없습니다. 근거 조항은 <EntityName entity={action.basis.entity} /> 입니다.
          </p>
        ) : (
          <Path scope={scope} entityId={action.target.entityId} onRetry={() => void loadScope()} />
        )}
      </section>

      <section aria-label="이력">
        <h2>이력</h2>
        {history.kind === "loading" && <p role="status">이력을 불러오는 중입니다…</p>}
        {history.kind === "failed" && (
          <p role="alert">
            {history.message}{" "}
            <button type="button" onClick={() => void loadDetail()}>
              다시 불러오기
            </button>
          </p>
        )}
        {history.kind === "loaded" && <History history={history.value} />}
      </section>

      <section aria-label="확정과 보류">
        <h2>확정과 보류</h2>
        {refused && <p role="alert">{refused}</p>}
        {status === "CONFIRMED" ? (
          <p>확정된 조치는 바꿀 수 없습니다.</p>
        ) : (
          <form onSubmit={(event) => event.preventDefault()}>
            <p>
              <label htmlFor={`note-${action.id}`}>사유</label>{" "}
              <textarea
                id={`note-${action.id}`}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={2}
                cols={60}
              />
              <br />
              <small>보류에는 사유를 반드시 적습니다. 확정에는 적지 않아도 됩니다.</small>
            </p>
            <button type="button" disabled={deciding} onClick={() => void decide("CONFIRM")}>
              확정
            </button>{" "}
            {status === "PENDING" && (
              <button type="button" disabled={deciding} onClick={() => void decide("HOLD")}>
                보류
              </button>
            )}
          </form>
        )}
      </section>
    </div>
  );
}

function Path({
  scope,
  entityId,
  onRetry,
}: {
  scope: Loaded<ChangeImpactScope> | null;
  entityId: string;
  onRetry: () => void;
}) {
  if (scope === null || scope.kind === "loading") {
    return <p role="status">근거 경로를 불러오는 중입니다…</p>;
  }
  if (scope.kind === "failed") {
    return (
      <p role="alert">
        {scope.message}{" "}
        <button type="button" onClick={onRetry}>
          다시 불러오기
        </button>
      </p>
    );
  }
  // 그 변동의 영향 범위에서 이 조치의 대상을 찾는다. 찾는 일이지 계산이 아니다.
  const target: ImpactTarget | undefined = scope.value.targets.find((candidate) => candidate.entityId === entityId);
  if (!target) {
    return <p role="alert">이 조치의 대상이 그 변동의 영향 범위에 없습니다. 관리자에게 알려 주세요.</p>;
  }
  return (
    <>
      <p>
        시작한 변동 대상 <EntityName entity={target.path.start} /> 에서 관계 {target.depth}개를 건너 닿았습니다.{" "}
        <strong data-testid="path-basis">{pathBasis(target.path.asOf, scope.value)}</strong>: 관계는{" "}
        <time>{target.path.asOf}</time> 에 유효한 것입니다. 이 변동의 영향 범위는 판의 적용일{" "}
        {scope.value.asOfEffective} 과 그 전날 {scope.value.asOfPrevious}, 두 날짜의 관계로 보았습니다.
      </p>
      <ol>
        {target.path.steps.map((step) => (
          <li key={step.stepNo} data-relation-id={step.relationId}>
            {step.relationType}: <EntityName entity={step.source} /> → <EntityName entity={step.target} />
          </li>
        ))}
      </ol>
    </>
  );
}

// 근거 경로가 어느 날짜의 관계로 닿았는지를 말로 적는다 (D1 5절의 용어, D2 AD-327).
// 날짜는 서버가 준 값이다: 경로의 날짜가 판의 적용일이면 변동 후, 그 전날이면 변동 전이다.
function pathBasis(asOf: string, scope: ChangeImpactScope): string {
  if (asOf === scope.asOfEffective) {
    return "변동 후의 관계";
  }
  if (asOf === scope.asOfPrevious) {
    return "변동 전의 관계";
  }
  // 두 날짜 가운데 어느 것도 아니면 말을 붙이지 않는다. 날짜는 옆에 그대로 보인다.
  return "관계의 날짜";
}

function History({ history }: { history: ActionDecision[] }) {
  // 시각은 설치 설정의 시간대로 보인다 (내 정보의 timeZone).
  const timeZone = useContext(SessionContext)?.timeZone;
  if (history.length === 0) {
    return <p>아직 확정하거나 보류한 적이 없습니다.</p>;
  }
  return (
    <table>
      <caption>이력</caption>
      <thead>
        <tr>
          <th scope="col">차례</th>
          <th scope="col">바뀜</th>
          <th scope="col">누가</th>
          <th scope="col">언제 (협정 세계시)</th>
          <th scope="col">사유</th>
        </tr>
      </thead>
      <tbody>
        {history.map((decision) => (
          <tr key={decision.seq}>
            <td>{decision.seq}</td>
            <td>
              {STATUS_LABEL[decision.fromStatus] ?? decision.fromStatus} → {STATUS_LABEL[decision.toStatus] ?? decision.toStatus}
            </td>
            <td>{decision.decidedBy}</td>
            <td>
              {/* 설치 설정의 시간대로 바꿔 보인다. 서버가 준 값은 그대로 남겨 둔다. */}
              <time dateTime={decision.decidedAt} title={`협정 세계시 ${decision.decidedAt}`}>
                {formatInstant(decision.decidedAt, timeZone)}
              </time>
            </td>
            <td>{decision.note ?? ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
