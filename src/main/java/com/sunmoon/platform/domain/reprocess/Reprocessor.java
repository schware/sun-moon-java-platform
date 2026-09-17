package com.sunmoon.platform.domain.reprocess;

import java.util.List;

/**
 * 실패한 것을 <b>다시 하게 만드는</b> 자리 (업무 정의서 §5.8, §10).
 *
 * <p>지금까지 실패는 세 군데에 적히기만 했습니다 — 매출 분해, 마스터
 * 적용, 프로그램 설치. 기록은 남는데 되돌릴 길이 없어서, 실서버의
 * 거래 하나가 이틀째 FAILED 로 있었습니다.
 *
 * <p>셋을 따로 만들지 않은 이유: <b>모양이 같습니다.</b> 무엇이
 * 실패했는지 보여주고, 다시 하게 만들고, 몇 번째인지 센다 — 세 번째쯤
 * 만들면 모양이 갈라지고, 갈라진 뒤에는 화면도 셋이 됩니다.
 *
 * <p><b>"다시 한다"가 무엇인지는 종류마다 다릅니다</b>, 그리고 그
 * 차이가 이 인터페이스에서 제일 중요한 대목입니다:
 *
 * <ul>
 *   <li><b>매출 분해</b>는 이 서버가 직접 다시 합니다. 원본이 있으니
 *       상태만 되돌리면 배치가 다음 회차에 집어 갑니다.
 *   <li><b>마스터·프로그램</b>은 <b>장비가 가져갑니다</b>. 서버가 밀
 *       수 없으므로 여기서 할 수 있는 것은 실패 표시를 지워 "다시
 *       받아야 할 것"으로 되돌리는 것까지이고, 실제 재전송은 장비가
 *       다음에 물어볼 때 일어납니다. 그 사실을 답에 적어서 운영자가
 *       "눌렀는데 왜 안 바뀌지"를 겪지 않게 합니다.
 * </ul>
 */
public interface Reprocessor {

    /** 주소와 화면에 쓰이는 이름 — {@code SALE}, {@code MASTER}, {@code PROGRAM}. */
    String kind();

    /** 사람이 읽는 이름. */
    String label();

    /** 지금 실패해 있는 것들. 없으면 빈 목록입니다. */
    List<FailedItem> failed(String storeId);

    /**
     * 한 건을 다시 하게 만듭니다.
     *
     * @return 무슨 일이 일어났는지. 눌렀는데 아무 일도 안 일어난 것처럼
     *         보이면 운영자는 계속 누릅니다.
     */
    Outcome retry(String storeId, String id);

    /**
     * @param accepted 그 건이 있었고, 이제 다시 할 대상이 됐는가.
     * @param done     이 서버가 <b>지금</b> 다시 했는가. 거짓이면 대상으로
     *                 되돌려 놓았을 뿐이고, 실제 처리는 나중입니다.
     * @param message  화면에 그대로 뜨는 문장.
     *
     * <p>{@code accepted} 와 {@code done} 을 가른 이유: 일괄 재처리가
     * "몇 건을 걸었나"를 세야 하는데, 문장을 들여다봐서 세면 문장을
     * 고치는 날 숫자가 틀어집니다.
     */
    record Outcome(boolean accepted, boolean done, String message) {

        /** 걸어 뒀습니다. 실제 처리는 배치나 장비가 나중에 합니다. */
        public static Outcome queued(String message) {
            return new Outcome(true, false, message);
        }

        /** 지금 다시 했습니다. */
        public static Outcome done(String message) {
            return new Outcome(true, true, message);
        }

        /** 그런 건이 없습니다 — 이미 처리됐거나 애초에 없었습니다. */
        public static Outcome notFound(String id) {
            return new Outcome(false, false, "그런 건이 없습니다: " + id);
        }
    }
}
