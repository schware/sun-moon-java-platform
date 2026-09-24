package com.sunmoon.platform.domain.terminal;

import java.util.regex.Pattern;

/**
 * The default {@link DeviceDirectory}: accepts any id that looks like one.
 *
 * <p>**This is not authentication.** It rejects blank, overlong and
 * obviously malformed ids, which stops a mistyped client from occupying a
 * slot in the registry — and nothing more. A terminal presenting
 * {@code pos-99} that BO has never heard of is let in.
 *
 * <p>The real implementation asks BO, whose Device screen owns this master
 * data. It is not written yet because BO's API requires an operator
 * session, and service-to-service credentials are a decision that has not
 * been made. Deliberately a named gap rather than a silent one.
 */
public final class AcceptKnownFormatDirectory implements DeviceDirectory {

    private static final Pattern DEVICE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}");

    /**
     * 매장과 종류는 <b>보지 않습니다</b>. 볼 것이 없어서입니다 — 이
     * 구현이 아는 것은 모양뿐이고, 모양은 매장마다 다르지 않습니다.
     * 파라미터를 받아 두는 것은 여기서 쓰라는 뜻이 아니라, BO를 보는
     * 구현이 그것들을 필요로 하기 때문입니다.
     */
    @Override
    public boolean isRegistered(String storeId, String deviceId, String type) {
        return deviceId != null && DEVICE_ID.matcher(deviceId).matches();
    }
}
