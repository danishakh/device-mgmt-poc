package com.danishakh.att.devicemgmt.model;

import com.danishakh.att.devicemgmt.statemachine.DeviceCallbackResult;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeTransition;
import lombok.Getter;

/**
 * What a callback did to the database: an unknown command key, an event with no matching upgrade, or a
 * transition for the matched row (written when {@link #isWritten()} is true).
 */
@Getter
public final class WriteOutcome {
    private final boolean unknownCommandKey;
    /** Why nothing was matched. Set only when there is no transition. */
    private final DeviceCallbackResult result;
    /** The transition for the matched row. Null only when there is no matched row. */
    private final DeviceUpgradeTransition transition;

    private WriteOutcome(boolean unknownCommandKey, DeviceCallbackResult result, DeviceUpgradeTransition transition) {
        this.unknownCommandKey = unknownCommandKey;
        this.result = result;
        this.transition = transition;
    }

    /**
     * No upgrade has this command key for the device.
     *
     * @return the outcome for an unknown key
     */
    public static WriteOutcome unknownCommandKey() {
        return new WriteOutcome(true, null, null);
    }

    /**
     * No upgrade matched the event, and the event is not a TransferComplete with an unknown key.
     *
     * @return the outcome for an ignored event
     */
    public static WriteOutcome ignored() {
        return new WriteOutcome(false, DeviceCallbackResult.IGNORED, null);
    }

    /**
     * The matched row's transition. Written when it changed the row.
     *
     * @param transition the transition for the matched row
     * @return the outcome for a matched row
     */
    public static WriteOutcome of(DeviceUpgradeTransition transition) {
        return new WriteOutcome(false, null, transition);
    }

    /**
     * True when the matched row's transition changed the row and the write won.
     *
     * @return whether the row was written
     */
    public boolean isWritten() {
        return transition != null && transition.changed();
    }
}

