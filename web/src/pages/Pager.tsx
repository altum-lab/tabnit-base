// 나눠 받은 목록을 넘기는 줄 (D7 2절 "나눠 주기", D2 AD-347).
//
// 전체 수, 건너뛴 수, 한 번에 받는 수는 서버가 답한 값이다. 화면은 목록을 세지 않는다.
// 여기서 하는 셈은 어디를 보고 있는지 적는 것(몇 번째부터 몇 번째까지)과 옮겨 갈 자리뿐이다.

type Props = {
  // 서버가 답한 전체 수, 건너뛴 수, 한 번에 주는 수.
  total: number;
  offset: number;
  limit: number;
  // 이 쪽에 온 항목의 수. 서버가 준 목록의 길이다.
  shown: number;
  // 옮겨 갈 건너뛸 수를 받는다.
  onMove: (offset: number) => void;
  disabled?: boolean;
};

export function Pager({ total, offset, limit, shown, onMove, disabled = false }: Props) {
  const hasPrevious = offset > 0;
  const hasNext = offset + shown < total;
  return (
    <p data-testid="pager">
      <span data-testid="pager-position">
        {shown > 0
          ? `전체 ${total}건 가운데 ${offset + 1}~${offset + shown}번째`
          : `전체 ${total}건. 이 자리에는 항목이 없습니다`}
      </span>{" "}
      <button type="button" onClick={() => onMove(Math.max(0, offset - limit))} disabled={disabled || !hasPrevious}>
        이전
      </button>{" "}
      <button type="button" onClick={() => onMove(offset + limit)} disabled={disabled || !hasNext}>
        다음
      </button>
    </p>
  );
}
