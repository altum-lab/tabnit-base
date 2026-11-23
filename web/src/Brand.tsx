import mark from "./assets/tabnit-mark-on-dark.svg";

// 제품의 마크와 이름 (D2 AD-366). 위 띠와 로그인 화면이 함께 쓴다.
// 마크는 어두운 바탕용으로 그려진 것이라 어두운 바탕 위에만 둔다 (styles.css 의 .brand).
export function Brand() {
  return (
    <p className="brand">
      {/* 이름이 바로 옆에 글자로 있어 그림에는 따로 설명을 붙이지 않는다. */}
      <img src={mark} alt="" data-testid="brand-mark" />
      <strong>Tabnit Base</strong>
    </p>
  );
}
