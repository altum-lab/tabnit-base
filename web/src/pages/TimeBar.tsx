import { useEffect, useRef } from "react";
import { addDays } from "../time";

// 시점 막대 (D2 AD-353). 하루 단위로 미는 막대다. 범위는 가운데 날짜(base)의 앞뒤 1년이다.
// 미는 동안에는 가리키는 날짜만 바꿔 보여 주고, 놓으면 (또는 글쇠로 옮기면) 그 날짜를 알린다.
// 관계 지도와 Pack 전체 보기가 함께 쓴다. 시험이 찾는 이름은 id 에서 만든다 (id-from, id-to, id-date).

export const BAR_DAYS = 365;

export function TimeBar({
  id,
  label,
  base,
  offset,
  disabled,
  onSlide,
  onCommit,
}: {
  id: string;
  label: string;
  base: string;
  offset: number;
  // 받아 오는 동안에는 옮기지 못한다: 청한 것이 끝나기 전에 다른 것을 청하지 않는다.
  disabled: boolean;
  onSlide: (offset: number) => void;
  onCommit: (offset: number) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const commit = useRef(onCommit);
  commit.current = onCommit;

  // 놓았을 때를 알려 주는 것은 브라우저의 change 이벤트다. React 의 onChange 는 미는 동안 계속 온다.
  useEffect(() => {
    const element = input.current;
    if (!element) {
      return;
    }
    const listener = () => commit.current(Number(element.value));
    element.addEventListener("change", listener);
    return () => element.removeEventListener("change", listener);
  }, []);

  return (
    <p>
      <label htmlFor={id}>{label}</label> <small data-testid={`${id}-from`}>{addDays(base, -BAR_DAYS)}</small>{" "}
      <input
        id={id}
        ref={input}
        type="range"
        min={-BAR_DAYS}
        max={BAR_DAYS}
        step={1}
        value={offset}
        disabled={disabled}
        onChange={(e) => onSlide(Number(e.target.value))}
        style={{ width: "24rem", verticalAlign: "middle" }}
      />{" "}
      <small data-testid={`${id}-to`}>{addDays(base, BAR_DAYS)}</small>{" "}
      <strong data-testid={`${id}-date`}>{addDays(base, offset)}</strong>
    </p>
  );
}
