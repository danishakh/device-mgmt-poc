package com.danishakh.att.devicemgmt.model;
import com.danishakh.att.devicemgmt.statemachine.OutcomeReason;
import com.danishakh.att.devicemgmt.statemachine.TrailingEvidence;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState;
import lombok.Builder;
import lombok.Getter;
/** How many device upgrades of a campaign share one state, outcome reason and trailing evidence. */
@Getter
@Builder
public class UpgradeBreakdown {
    private final DeviceUpgradeState state;
    /** Null while the upgrade has no outcome yet. */
    private final OutcomeReason outcomeReason;
    /** Null when no trailing evidence has arrived. */
    private final TrailingEvidence trailingEvidence;
    /** Set when {@link #trailingEvidence} is FAULT. */
    private final Integer trailingFaultCode;
    private final int count;
}
