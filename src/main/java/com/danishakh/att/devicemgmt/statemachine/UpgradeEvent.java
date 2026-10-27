package com.danishakh.att.devicemgmt.statemachine;

/**
 * Everything that can happen to one device upgrade.
 *
 * <p>This is a sealed interface: the four kinds below ({@link Tick}, {@link Deadline},
 * {@link Inform}, {@link Ack}) are the only implementations allowed. That lets
 * {@link UpgradeStateMachine#apply} use a {@code switch} that the compiler checks is
 * exhaustive. Each kind is written as a small, final, immutable class instead of a record,
 * so the shape of the data is spelled out explicitly rather than relying on record syntax.
 */
public sealed interface UpgradeEvent
        permits UpgradeEvent.Tick, UpgradeEvent.Deadline, UpgradeEvent.Inform, UpgradeEvent.Ack {

    /**
     * Fired by the scheduler on every tick.
     *
     * <p>{@code mayStart} is computed by the caller: true only when the campaign is RUNNING
     * and the number of in-flight devices is below {@code maxConcurrent}. The state machine
     * trusts this flag; it does not look at the campaign itself.
     */
    final class Tick implements UpgradeEvent {
        private final boolean mayStart;

        public Tick(boolean mayStart) {
            this.mayStart = mayStart;
        }

        /** True when the campaign is RUNNING and a slot is free. */
        public boolean mayStart() {
            return mayStart;
        }
    }

    /**
     * Fired when a row's {@code deadline_at} has passed. The caller finds overdue rows with a
     * database query; the event itself carries no data.
     */
    final class Deadline implements UpgradeEvent {
    }

    /**
     * A device calling in with an {@code inform}.
     *
     * <p>Per decision D12, a non-null {@code transferComplete} makes this a TransferComplete,
     * whatever {@code informEvent} says. {@code informEvent} is only the label the device chose for the call.
     */
    final class Inform implements UpgradeEvent {
        private final InformEvent informEvent;
        private final String firmwareVersion;
        private final TransferResult transferComplete;

        public Inform(InformEvent informEvent, String firmwareVersion, TransferResult transferComplete) {
            this.informEvent = informEvent;
            this.firmwareVersion = firmwareVersion;
            this.transferComplete = transferComplete;
        }

        /** The reason the device gave for calling in. */
        public InformEvent informEvent() {
            return informEvent;
        }

        /** The firmware version the device reported. */
        public String firmwareVersion() {
            return firmwareVersion;
        }

        /** The TransferComplete payload, or null if this inform does not carry one. */
        public TransferResult transferComplete() {
            return transferComplete;
        }
    }

    /**
     * The device's {@code command-response} to a Download: ACCEPTED or REJECTED.
     */
    final class Ack implements UpgradeEvent {
        private final String commandKey;
        private final AckStatus status;

        public Ack(String commandKey, AckStatus status) {
            this.commandKey = commandKey;
            this.status = status;
        }

        /** The commandKey the device is acknowledging. */
        public String commandKey() {
            return commandKey;
        }

        /** Whether the device accepted or rejected the command. */
        public AckStatus status() {
            return status;
        }
    }

    /**
     * The payload of a TransferComplete: the commandKey it reports against, and a fault code
     * where {@code 0} means success.
     */
    final class TransferResult {
        private final String commandKey;
        private final int faultCode;

        public TransferResult(String commandKey, int faultCode) {
            this.commandKey = commandKey;
            this.faultCode = faultCode;
        }

        /** The commandKey this TransferComplete reports against. */
        public String commandKey() {
            return commandKey;
        }

        /** 0 means success. Anything else is a device-reported fault. */
        public int faultCode() {
            return faultCode;
        }
    }
}
