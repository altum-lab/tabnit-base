import { type FormEvent, useEffect, useRef, useState } from "react";
import { api, type EntityList, type PackSummary } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";
import { Pager } from "./Pager";

// 개체를 글자로 찾아 고르는 칸 (카드 A-17, D7 4절 "개체 찾기", D2 AD-411).
// 서버의 listPacks 와 searchEntities (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 칸은 계산하지 않는다.
// - 무엇이 맞는지 가리지 않는다. 후보와 순서, 전체 수는 서버가 준 것이다.
// - 오늘을 정하지 않는다. 조회 시점이 비어 있으면 서버가 정하고, 서버가 답한 날짜를 보여 준다.

type Packs =
  | { kind: "loading" }
  | { kind: "loaded"; packs: PackSummary[] }
  | { kind: "failed"; message: string };

type Found =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; list: EntityList }
  | { kind: "failed"; message: string };

type Submitted = { packId: string; q: string; entityType?: string[] };

type Props = {
  // 화면의 조회 시점. 비어 있으면 보내지 않는다.
  asOf: string;
  // 고른 개체의 id 를 넘긴다.
  onPick: (entityId: string) => void;
  // 고른 개체를 다른 곳에 넣는 단추들 (경로 찾기의 출발과 도착, 카드 A-18). 없으면 "고르기" 하나다.
  extras?: { label: string; onPick: (entityId: string) => void }[];
  disabled?: boolean;
  // 유형으로 거르기 (카드 A-20: 전체 지도의 유형 묶음을 누르면 온다). 주어지면 그 Pack 의 그 유형의 개체를 찾아 보인다.
  // 서버는 그 유형과 그 하위 유형의 개체를 준다 (D7 4절 "개체 찾기").
  typeFilter?: { packId: string; entityType: string; label: string } | null;
  onClearTypeFilter?: () => void;
};

export function EntityPicker({ asOf, onPick, extras = [], disabled = false, typeFilter = null, onClearTypeFilter }: Props) {
  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  const [packId, setPackId] = useState("");
  const [text, setText] = useState("");
  const [found, setFound] = useState<Found>({ kind: "idle" });
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  async function loadPacks() {
    setPacks({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/packs");
      if (data) {
        setPacks({ kind: "loaded", packs: data.packs });
        // 고를 것이 하나뿐이면 그것으로 둔다.
        const only = data.packs[0];
        if (data.packs.length === 1 && only) {
          setPackId(only.packId);
        }
      } else {
        setPacks({ kind: "failed", message: messageOf(error, response.status) });
      }
    } catch {
      setPacks({ kind: "failed", message: CONNECTION_FAILED });
    }
  }

  useEffect(() => {
    void loadPacks();
  }, []);

  // 거르기를 "풀기" 단추로 풀었는가. 그때는 거르지 않은 목록으로 다시 찾는다.
  const researchOnClear = useRef(false);
  // 거르는 유형의 안내. 유형을 누르는 곳(전체 보기)은 화면의 맨 아래에 있다: 안내로 초점을 옮겨 화면에 보이게 한다.
  const filterNote = useRef<HTMLParagraphElement>(null);

  // 유형으로 거르라는 것이 오면 그 Pack 의 그 유형의 개체를 바로 찾는다. 검색어는 비운다.
  useEffect(() => {
    if (typeFilter) {
      setPackId(typeFilter.packId);
      setText("");
      void search(0, { packId: typeFilter.packId, text: "" });
      filterNote.current?.focus();
      filterNote.current?.scrollIntoView({ block: "nearest" });
    } else if (researchOnClear.current) {
      // 풀었다: 걸러진 목록을 그대로 두지 않는다. 지금의 Pack 과 검색어로 거르지 않고 다시 찾는다.
      researchOnClear.current = false;
      void search(0);
    }
    // 거르는 것이 바뀔 때만 다시 찾는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typeFilter]);

  // 다른 Pack 을 고르면 유형 거르기를 푼다: 거르는 유형은 그 Pack 의 것이다. 앞의 Pack 의 걸러진 목록도 비운다.
  function choosePack(next: string) {
    setPackId(next);
    if (typeFilter && next !== typeFilter.packId) {
      latest.current++;
      submitted.current = null;
      setFound({ kind: "idle" });
      onClearTypeFilter?.();
    }
  }

  // 마지막으로 찾은 조건 (Pack, 검색어, 거르는 유형). 화면의 목록은 이 조건의 것이다: 다른 쪽을 받을 때와 조회 시점이
  // 바뀌어 다시 받을 때 이것을 쓴다. 칸에 적어 두기만 한 값으로 묻지 않는다.
  const submitted = useRef<Submitted | null>(null);

  // 칸에 적힌 값으로 새로 찾는다 (찾기 단추, 유형 거르기).
  async function search(offset: number, given?: { packId: string; text: string }) {
    const wantedPack = given?.packId ?? packId;
    submitted.current = {
      packId: wantedPack,
      q: given?.text ?? text,
      // 거르는 유형은 그 Pack 을 볼 때만 보낸다.
      ...(typeFilter && typeFilter.packId === wantedPack ? { entityType: [typeFilter.entityType] } : {}),
    };
    await fetchPage(submitted.current, offset);
  }

  // 화면의 조회 시점이 바뀌면 찾아 둔 후보를 그 날짜로 다시 받는다 (찾기를 다시 누르지 않는다): 지도와 관계 목록이
  // 새 날짜를 보는데 후보와 표시 이름만 앞 날짜의 것으로 남지 않는다.
  useEffect(() => {
    if (submitted.current) {
      void fetchPage(submitted.current, 0);
    }
    // 조회 시점이 바뀔 때만 다시 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asOf]);

  // 그 조건의 한 쪽을 받는다. 조회 시점은 화면의 것이다.
  async function fetchPage(wanted: Submitted, offset: number) {
    const turn = ++latest.current;
    setFound({ kind: "loading" });
    try {
      const query = { ...wanted, offset, ...(asOf ? { asOf } : {}) };
      const { data, error, response } = await api.GET("/api/v1/entities", { params: { query } });
      if (turn !== latest.current) {
        return;
      }
      setFound(data ? { kind: "loaded", list: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setFound({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void search(0);
  }

  return (
    <section className="entity-picker" aria-labelledby="entity-picker-title">
      <h2 id="entity-picker-title">개체 찾기</h2>
      <p>
        <small>
          개체의 id 를 몰라도 키, 이름, 속성 값의 일부로 찾아 고를 수 있습니다. 이름과 속성 값은 아래의 조회 시점에 유효한
          값으로 찾습니다.
        </small>
      </p>

      {/* 이 줄에는 대기 상태의 역할(status)을 주지 않는다: 화면의 대기 상태는 관계를 받아 오는 것 하나다. */}
      {packs.kind === "loading" && <p>Pack 의 목록을 불러오는 중입니다…</p>}
      {packs.kind === "failed" && (
        <p role="alert">
          {packs.message}{" "}
          <button type="button" onClick={() => void loadPacks()}>
            다시 불러오기
          </button>
        </p>
      )}
      {packs.kind === "loaded" && packs.packs.length === 0 && (
        <p>적재된 Pack 이 없습니다. 관리자가 Pack 을 적재한 뒤에 찾을 수 있습니다.</p>
      )}

      {packs.kind === "loaded" && packs.packs.length > 0 && (
        <form onSubmit={submit}>
          <p>
            <label htmlFor="picker-pack">Pack</label>{" "}
            <select id="picker-pack" value={packId} onChange={(e) => choosePack(e.target.value)} required>
              <option value="">Pack 을 고르세요</option>
              {packs.packs.map((candidate) => (
                <option key={candidate.packId} value={candidate.packId}>
                  {candidate.label} ({candidate.packId})
                </option>
              ))}
            </select>{" "}
            <label htmlFor="picker-text">검색어</label>{" "}
            <input
              id="picker-text"
              name="q"
              value={text}
              maxLength={200}
              onChange={(e) => setText(e.target.value)}
              size={24}
            />{" "}
            <button type="submit" disabled={found.kind === "loading"}>
              찾기
            </button>{" "}
            <small>검색어를 비우면 그 Pack 의 개체를 모두 봅니다.</small>
          </p>
        </form>
      )}

      {typeFilter && (
        <p data-testid="picker-type-filter" ref={filterNote} tabIndex={-1}>
          {/* 이름표와 이름이 같으면 한 번만 적는다. */}
          유형 <strong>{typeFilter.label}</strong>
          {typeFilter.label !== typeFilter.entityType && (
            <>
              {" "}
              (<code>{typeFilter.entityType}</code>)
            </>
          )}
          의 개체만 찾습니다.{" "}
          <button
            type="button"
            onClick={() => {
              researchOnClear.current = true;
              onClearTypeFilter?.();
            }}
          >
            유형 거르기 풀기
          </button>
        </p>
      )}

      {found.kind === "loading" && <p role="status">개체를 찾는 중입니다…</p>}
      {found.kind === "failed" && <p role="alert">{found.message}</p>}
      {found.kind === "loaded" && (
        <>
          <p>
            조회 시점 <span data-testid="searched-as-of">{found.list.asOf}</span> 의 값으로 찾았습니다.
          </p>
          {found.list.total === 0 ? (
            <p>맞는 개체가 없습니다. 검색어나 조회 시점을 바꿔 다시 찾아보세요.</p>
          ) : (
            <>
              <Pager
                total={found.list.total}
                offset={found.list.offset}
                limit={found.list.limit}
                shown={found.list.entities.length}
                // 다른 쪽은 화면의 목록을 만든 그 조건으로 받는다.
                onMove={(offset) => submitted.current && void fetchPage(submitted.current, offset)}
              />
              {found.list.entities.length > 0 && (
                <table aria-label="찾은 개체">
                  <thead>
                    <tr>
                      <th scope="col">개체</th>
                      <th scope="col">고르기</th>
                    </tr>
                  </thead>
                  <tbody>
                    {found.list.entities.map((entity) => (
                      <tr key={entity.entityId} data-entity-id={entity.entityId}>
                        <td>
                          <EntityName entity={entity} />
                        </td>
                        <td>
                          <button type="button" disabled={disabled} onClick={() => onPick(entity.entityId)}>
                            고르기
                          </button>
                          {extras.map((extra) => (
                            <span key={extra.label}>
                              {" "}
                              <button type="button" onClick={() => extra.onPick(entity.entityId)}>
                                {extra.label}
                              </button>
                            </span>
                          ))}
                        </td>
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
