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

    @Override
    public boolean isRegistered(String deviceId) {
        return deviceId != null && DEVICE_ID.matcher(deviceId).matches();
    }
}
