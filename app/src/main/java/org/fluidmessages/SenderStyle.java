package org.fluidmessages;

import java.util.Collection;

enum SenderStyle {
    AUTOMATED, SMS, RCS;

    static SenderStyle infer(String address) {
        String value = address.trim();
        if (value.matches("[0-9]{5,6}") ||
            (value.matches("[A-Za-z0-9 ._-]{2,20}") && value.matches(".*[A-Za-z].*"))) {
            return AUTOMATED;
        }
        return SMS;
    }

    static SenderStyle group(Collection<SenderStyle> members) {
        if (members.contains(AUTOMATED)) return AUTOMATED;
        return members.isEmpty() || members.contains(SMS) ? SMS : RCS;
    }
}
