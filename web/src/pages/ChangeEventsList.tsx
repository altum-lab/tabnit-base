import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { api, type ChangeEventList } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";
import { Pager } from "./Pager";

// Pack 의 변동 이벤트의 목록 (카드 C-15, D7 5절 "변동 이벤트의 목록", D2 AD-417). 변동 감지 화면에 들어간다.
// 서버의 listChangeEvents (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 목록은 계산하지 않는다.
// - 줄의 차례(가장 최근에 받은 판의 변동부터)와 전체 수, 조치의 수, 영향 대상의 수는 서버가 준 것이다.
// - 영향 대상의 수가 없는 것(null)과 0 을 가려 보인다: 없음은 영향 범위를 계산하지 못한 것이고 0 은 영향이 없는 것이다.
// - 영향 범위는 변동마다 하나라, 이 목록이 영향 범위의 이력이기도 하다. 줄에서 그 변동의 영향 범위와 조치로 간다.

type Status = "" | "PENDING" | "PROCESSED" | "FAILED";

const STATUSES: { value: Exclude<Status, "">; label: string }[] = [
  { value: "PENDING", label: "대기" },
  { value: "PROCESSED", label: "처리됨" },
  { value: "FAILED", label: "실패" },
];

const KINDS: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

type View =
  | { kind: "loading" }
  | { kind: "loaded"; list: ChangeEventList }
  | { kind: "failed"; message: string };

type Props = {
  packId: string;
  // 판을 올릴 때마다 바뀌는 값. 목록을 첫 쪽부터 다시 받게 한다.
  refreshKey: number;
};

export function ChangeEventsList({ packId, refreshKey }: Props) {
  const [status, setStatus] = useState<Status>("");
  const [offset, setOffset] = useState(0);
  const [seenRefresh, setSeenRefresh] = useState(refreshKey);
  const [view, setView] = useState<View>({ kind: "loading" });
  const latest = useRef(0);

  // 판을 올리면 첫 쪽부터 다시 본다. 그리는 중에 맞춘다: 따로 효과로 맞추면 옛 자리로 한 번 더 묻게 된다.
  if (seenRefresh !== refreshKey) {
    setSeenRefresh(refreshKey);
    setOffset(0);
  }

  useEffect(() => {
    // 가장 나중에 보낸 요청의 답만 쓴다.
    const request = ++latest.current;
    setView((current) => (current.kind === "loaded" ? current : { kind: "loading" }));
    void (async () => {
      try {
        const query = { packId, offset, ...(status ? { status } : {}) };
        const { data, error, response } = await api.GET("/api/v1/change-events", { params: { query } });
        if (request !== latest.current) {
          return;
        }
        setView(data ? { kind: "loaded", list: data } : { kind: "failed", message: messageOf(error, response.status) });
      } catch {
        if (request === latest.current) {
          setView({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    })();
  }, [packId, status, offset, refreshKey]);

  function filter(next: Status) {
    setStatus(next);
    setOffset(0);
  }

  return (
    <section aria-labelledby="change-events-title">
      <h2 id="change-events-title">변동 이벤트</h2>
      <p>
        이 Pack 의 변동을 판을 거치지 않고 봅니다. 가장 최근에 받은 판의 변동부터입니다. 변동마다 영향 범위가 하나라, 영향
        범위를 계산한 이력이기도 합니다.
      </p>
      <p>
        <label htmlFor="change-events-status">상태</label>{" "}
        <select id="change-events-status" value={status} onChange={(e) => filter(e.target.value as Status)}>
          <option value="">모두</option>
          {STATUSES.map((candidate) => (
            <option key={candidate.value} value={candidate.value}>
              {candidate.label}
            </option>
          ))}
        </select>
      </p>

      {view.kind === "loading" && <p role="status">변동 이벤트를 불러오는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && view.list.total === 0 && (
        <p role="status">{status ? "이 상태의 변동 이벤트가 없습니다." : "이 Pack 에는 변동 이벤트가 없습니다. 둘째 판부터 변동이 생깁니다."}</p>
      )}
      {view.kind === "loaded" && view.list.total > 0 && (
        <>
          <Pager
            total={view.list.total}
            offset={view.list.offset}
            limit={view.list.limit}
            shown={view.list.changes.length}
            onMove={setOffset}
          />
          {view.list.changes.length === 0 ? (
            <p role="status">이 자리에는 변동 이벤트가 없습니다. 이전으로 돌아가세요.</p>
          ) : (
            <table aria-label="변동 이벤트">
              <thead>
                <tr>
                  <th scope="col">판</th>
                  <th scope="col">항목</th>
                  <th scope="col">종류</th>
                  <th scope="col">주 개체</th>
                  <th scope="col">상태</th>
                  <th scope="col">조치의 수</th>
                  <th scope="col">영향 대상의 수</th>
                </tr>
              </thead>
              <tbody>
                {view.list.changes.map((change) => (
                  <tr key={change.changeEventId} data-listed-change-event-id={change.changeEventId}>
                    <td>
                      {change.inputSourceLabel} {change.editionNo}번째 판 <small>(적용일 {change.effectiveDate})</small>
                    </td>
                    <td>{change.itemKey}</td>
                    <td>{KINDS[change.kind] ?? change.kind}</td>
                    <td>
                      <EntityName entity={change.primaryEntity} />
                    </td>
                    <td>
                      {STATUSES.find((candidate) => candidate.value === change.status)?.label ?? change.status}
                      {change.failedStage !== null && (
                        <small>
                          {" "}
                          ({change.failedStage === "IMPACT" ? "영향 범위 계산" : "규칙 실행"}에서)
                        </small>
                      )}
                    </td>
                    <td>
                      <span data-testid="action-count">{change.actionCount}</span>
                      {change.actionCount > 0 && (
                        <>
                          {" "}
                          <Link
                            to={`/actions?${new URLSearchParams({ packId, status: "ALL", changeEventId: change.changeEventId })}`}
                          >
                            조치
                          </Link>
                        </>
                      )}
                    </td>
                    <td>
                      <span data-testid="impact-target-count">
                        {change.impactTargetCount === null ? "없음" : change.impactTargetCount}
                      </span>
                      {change.impactTargetCount !== null && (
                        <>
                          {" "}
                          <Link to={`/impact?${new URLSearchParams({ changeEventId: change.changeEventId })}`}>영향 범위</Link>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
