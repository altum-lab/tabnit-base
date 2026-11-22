package com.altumlab.tabnit.verify;

import com.altumlab.tabnit.verify.Product.Reply;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * 제품이 나눠 주는 목록을 끝까지 받아 하나로 잇는다 (D7 2절 "나눠 주기", D2 AD-347).
 *
 * <p>정답과는 목록 전체를 순서까지 견주기 때문이다. 제품이 준 순서를 바꾸지 않는다.
 */
final class PagedLists {

    private PagedLists() {
    }

    /**
     * 목록을 끝까지 받아 잇는다. 답의 모양은 한 번에 받은 것과 같다: 목록의 항목만 이어 붙이고 나머지는 첫 답의 것이다.
     * 200 이 아닌 답과 목록이 없는 답은 받은 그대로 돌려준다: 무엇이 틀렸는지는 견주는 쪽이 알린다.
     *
     * <p>받는 사이에 목록이 바뀌면 (쪽마다의 전체 수가 다르거나, 이은 수가 전체 수와 다르면) 이은 것이 한 시점의
     * 목록이 아니다. 처음부터 다시 받고, 정해진 횟수만큼 해도 그러면 알린다.
     *
     * @param fetch 건너뛸 수를 받아 그 쪽을 받아 오는 길
     * @param listKey 답에서 목록이 든 항목의 이름
     * @param pageSize 한 번에 받는 수. 받아 오는 길이 쓰는 값과 같아야 한다
     * @param maxAttempts 다시 받는 횟수의 한도
     * @param what 오류에 적을 이름 (무엇을 받다가인지)
     * @throws IllegalStateException 받을 때마다 목록이 바뀌었을 때
     */
    static Reply whole(IntFunction<Reply> fetch, String listKey, int pageSize, int maxAttempts, String what) {
        for (int attempt = 1;; attempt++) {
            Reply first = null;
            List<Object> joined = new ArrayList<>();
            Object total = null;
            boolean changed = false;
            for (int offset = 0;; offset += pageSize) {
                Reply reply = fetch.apply(offset);
                if (reply.status() != 200 || !(reply.body() instanceof Map<?, ?> body)
                        || !(body.get(listKey) instanceof List<?> items)) {
                    return reply;
                }
                if (first == null) {
                    first = reply;
                    total = body.get("total");
                }
                else if (!Objects.equals(total, body.get("total"))) {
                    changed = true;
                    break;
                }
                joined.addAll(items);
                if (items.size() < pageSize) {
                    break;
                }
            }
            // 전체 수는 같았는데 이은 수가 다르면, 받는 사이에 하나가 빠지고 하나가 들어온 것일 수 있다.
            if (!changed && total instanceof Number count && count.longValue() != joined.size()) {
                changed = true;
            }
            if (!changed) {
                Map<Object, Object> body = new LinkedHashMap<>((Map<?, ?>) first.body());
                body.put(listKey, joined);
                return new Reply(200, body);
            }
            if (attempt >= maxAttempts) {
                throw new IllegalStateException("나눠 받는 사이에 목록이 " + maxAttempts + "번 달라졌습니다: " + what
                        + ". 판을 올리는 일이 함께 돌고 있는지 확인하세요.");
            }
        }
    }
}
