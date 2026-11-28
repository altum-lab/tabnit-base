import { type FormEvent, useContext, useEffect, useRef, useState } from "react";
import { api, type AuditRecordList } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { SessionContext } from "../session";
import { formatInstant } from "../time";
import { Pager } from "./Pager";

// 감사 기록 화면 (카드 E-22, D7 9절 "감사 기록 보기", D2 AD-409, AD-414). 관리자만 본다.
// 서버의 listAuditRecords (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 줄을 거르거나 다시 정렬하거나 세지 않는다. 줄과 순서(가장 최근 것부터), 전체 수는 서버가 준 것이다.
// - 요청 하나의 두 줄(시작, 결과)을 묶지 않는다. 저장된 줄 그대로 보인다.
// - 날짜를 시각으로 바꾸지 않는다. 날짜를 그대로 보내고, 어느 시간대의 하루인지는 서버가 정한다.
//
// 메뉴를 감추는 것은 편의일 뿐이다: 권한은 서버가 확인한다.

type Outcome = "STARTED" | "SUCCESS" | "DENIED" | "FAILED";

const OUTCOMES: { value: Outcome; label: string }[] = [
  { value: "STARTED", label: "시작" },
  { value: "SUCCESS", label: "성공" },
  { value: "DENIED", label: "거부" },
  { value: "FAILED", label: "실패" },
];

type Filter = { actor: string; action: string; outcome: "" | Outcome; from: string; to: string };

const NO_FILTER: Filter = { actor: "", action: "", outcome: "", from: "", to: "" };

type View =
  | { kind: "loading" }
  | { kind: "loaded"; list: AuditRecordList }
  | { kind: "failed"; message: string };

export function AuditPage() {
  const session = useContext(SessionContext);
  const admin = session?.role === "ADMIN";
  // 칸에 적고 있는 조건과, 마지막으로 찾은 조건. 쪽을 넘길 때는 찾은 조건으로 넘긴다.
  const [draft, setDraft] = useState<Filter>(NO_FILTER);
  const searched = useRef<Filter>(NO_FILTER);
  // 찾았을 때의 맨 위 줄의 id. 쪽을 넘길 때 이 값을 보내 목록을 붙든다 (D2 AD-424): 쪽을 받는 일 자체가 감사 기록을
  // 더하므로, 붙들지 않으면 넘길 때마다 목록이 밀려 앞 쪽의 줄이 다시 나온다. 찾으면 놓고 새로 붙든다.
  const pinned = useRef<number | null>(null);
  const [view, setView] = useState<View>({ kind: "loading" });
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  // pin: 찾기(새로 붙든다)인가, 쪽 넘기기(붙든 것을 보낸다)인가.
  async function load(filter: Filter, offset: number, pin: "new" | "keep" = "new") {
    const turn = ++latest.current;
    searched.current = filter;
    if (pin === "new") {
      pinned.current = null;
    }
    setView({ kind: "loading" });
    try {
      const query = {
        ...(filter.actor ? { actor: filter.actor } : {}),
        ...(filter.action ? { action: filter.action } : {}),
        ...(filter.outcome ? { outcome: filter.outcome } : {}),
        ...(filter.from ? { from: filter.from } : {}),
        ...(filter.to ? { to: filter.to } : {}),
        ...(pinned.current !== null ? { maxId: pinned.current } : {}),
        offset,
      };
      const { data, error, response } = await api.GET("/api/v1/audit-records", { params: { query } });
      if (turn !== latest.current) {
        return;
      }
      if (data && pin === "new") {
        pinned.current = data.records[0]?.id ?? null;
      }
      setView(data ? { kind: "loaded", list: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  useEffect(() => {
    if (admin) {
      void load(NO_FILTER, 0);
    }
    // 처음 열릴 때 한 번 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admin]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // 서버는 글자 그대로 같은 줄만 찾는다. 붙여 넣다 딸려 온 앞뒤 빈칸은 떼고 찾고, 칸에도 뗀 값을 보인다.
    const trimmed = { ...draft, actor: draft.actor.trim(), action: draft.action.trim() };
    setDraft(trimmed);
    void load(trimmed, 0);
  }

  if (!admin) {
    return (
      <section aria-labelledby="audit-title">
        <h1 id="audit-title">감사 기록</h1>
        <p role="alert">감사 기록은 관리자만 볼 수 있습니다. 필요하면 관리자에게 요청하세요.</p>
      </section>
    );
  }

  return (
    <section aria-labelledby="audit-title">
      <h1 id="audit-title">감사 기록</h1>
      <p>
        누가 언제 무엇을 조회하거나 바꿨는지의 기록입니다. 가장 최근 것부터 보입니다. 요청 하나는 보통 두 줄입니다 (하기
        전의 "시작"과 끝난 뒤의 결과). 이 화면을 보는 것도 기록에 남습니다.
      </p>

      <form onSubmit={search}>
        <p>
          <label htmlFor="audit-actor">누가</label>{" "}
          <input
            id="audit-actor"
            name="actor"
            value={draft.actor}
            maxLength={200}
            onChange={(e) => setDraft({ ...draft, actor: e.target.value })}
            size={16}
          />{" "}
          <label htmlFor="audit-action">무엇을</label>{" "}
          <input
            id="audit-action"
            name="action"
            value={draft.action}
            maxLength={100}
            onChange={(e) => setDraft({ ...draft, action: e.target.value })}
            size={20}
          />{" "}
          <label htmlFor="audit-outcome">결과</label>{" "}
          <select
            id="audit-outcome"
            value={draft.outcome}
            onChange={(e) => setDraft({ ...draft, outcome: e.target.value as Filter["outcome"] })}
          >
            <option value="">모두</option>
            {OUTCOMES.map((outcome) => (
              <option key={outcome.value} value={outcome.value}>
                {outcome.label}
              </option>
            ))}
          </select>
        </p>
        <p>
          <label htmlFor="audit-from">시작 날짜</label>{" "}
          <input
            id="audit-from"
            name="from"
            type="date"
            min="0001-01-01"
            max="9999-12-31"
            value={draft.from}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })}
          />{" "}
          <label htmlFor="audit-to">끝 날짜</label>{" "}
          <input
            id="audit-to"
            name="to"
            type="date"
            min="0001-01-01"
            max="9999-12-31"
            value={draft.to}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })}
          />{" "}
          <button type="submit" disabled={view.kind === "loading"}>
            찾기
          </button>
        </p>
        <p>
          <small data-testid="audit-filter-hint">
            누가는 아이디, 무엇을은 기능의 이름(listPacks 따위)이 글자 그대로 같은 줄을 찾습니다. 끝 날짜의 하루도
            들어갑니다. 비운 조건은 거르지 않습니다. 역할이 모자라 막힌 시도는 그 기능의 이름이 아니라 accessDenied 로
            남습니다: 무엇을에 accessDenied 를 적어 찾고, 어느 주소였는지는 줄의 대상에서 봅니다.
          </small>
        </p>
      </form>

      {view.kind === "loading" && <p role="status">감사 기록을 불러오는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && (
        <>
          {view.list.total === 0 ? (
            <p role="status">맞는 감사 기록이 없습니다. 조건을 바꿔 다시 찾아보세요.</p>
          ) : (
            <>
              <Pager
                total={view.list.total}
                offset={view.list.offset}
                limit={view.list.limit}
                shown={view.list.records.length}
                onMove={(offset) => void load(searched.current, offset, "keep")}
              />
              {view.list.maxId !== null && (
                <p data-testid="audit-pinned">
                  <small>찾은 때까지의 기록입니다. 그 뒤에 남은 것을 보려면 찾기를 다시 누르세요.</small>
                </p>
              )}
              {view.list.records.length === 0 ? (
                <p role="status">이 자리에는 줄이 없습니다. 이전으로 돌아가세요.</p>
              ) : (
                <table aria-label="감사 기록" className="audit-records">
                  <thead>
                    <tr>
                      <th scope="col">번호</th>
                      <th scope="col">언제</th>
                      <th scope="col">누가</th>
                      <th scope="col">무엇을</th>
                      <th scope="col">대상</th>
                      <th scope="col">조회 시점</th>
                      <th scope="col">결과</th>
                      <th scope="col">내용</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.list.records.map((record) => (
                      <tr key={record.id} data-record-id={record.id}>
                        <td>{record.id}</td>
                        <td>
                          <time dateTime={record.occurredAt}>{formatInstant(record.occurredAt, session?.timeZone)}</time>
                        </td>
                        <td>{record.actor ?? <small>(모름)</small>}</td>
                        <td>
                          <code>{record.action}</code>
                        </td>
                        <td>{record.target ?? ""}</td>
                        <td>{record.asOf ?? ""}</td>
                        <td>
                          {OUTCOMES.find((outcome) => outcome.value === record.outcome)?.label ?? record.outcome}
                          {record.httpStatus !== null && <small> ({record.httpStatus})</small>}
                          {record.startedId !== null && <small> 시작의 줄 {record.startedId}</small>}
                        </td>
                        <td>{record.detail ?? ""}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
