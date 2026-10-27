# Device Management Controller

A controller that rolls firmware out across a fleet of home gateways through upgrade campaigns. The controller cannot reach a device directly. It can only ask the device to call in, and it gives commands while answering that call.

## Language

### Fleet

**Device**:
A home gateway, identified by its `deviceId`, that reports its current firmware version each time it calls in.
_Avoid_: CPE, gateway, node

**Fleet**:
All devices the simulator knows about.
_Avoid_: Inventory, population

**Eligible Device**:
A device whose firmware version matches a campaign's filter when the campaign is created.
_Avoid_: Target device

### Campaign

**Campaign**:
An operator's request to move every eligible device from one firmware version to a target version, with a concurrency limit and a failure threshold.
_Avoid_: Job, rollout, batch

**Target Version**:
The firmware version a campaign is installing.

**From Version**:
The firmware version a device was running when a campaign picked it. It never changes after that.
_Avoid_: Current version (this is a different thing)

**Paused**:
A campaign an operator stopped. It starts no new device upgrades and can be resumed.

**Halted**:
A campaign the controller stopped because its failure rate exceeded the threshold. It cannot be resumed.
_Avoid_: Paused (an operator action, not an automatic stop)

**Failure Rate**:
Failed plus timed-out device upgrades, divided by all finished device upgrades in a campaign. Devices that were already on the target version are not counted.

**Minimum Sample**:
How many device upgrades must finish before the failure rate is allowed to halt a campaign.

### Device upgrade

**Device Upgrade**:
One campaign's attempt to upgrade one device. It has its own lifecycle state.
_Avoid_: Job, task, attempt

**In-flight**:
A device upgrade that has started but has not finished: Notified, Download Sent or Accepted. The campaign's concurrency limit applies to this count.

**Finished**:
A device upgrade that is Succeeded, Failed or Timed Out.
_Avoid_: Done, complete

**Outcome Reason**:
Why a device upgrade finished the way it did, for example rejected, fault code, rolled back, no callback.

**Inferred Success**:
A device upgrade marked Succeeded because the device reported the target version without sending a TransferComplete.

**Rollback**:
A device that rebooted after accepting the download and came back on its from version without sending a TransferComplete.

**Late Evidence**:
A TransferComplete for a device upgrade that has already timed out.

### Protocol

**Connection Request**:
A message the controller sends to ask a device to call in. A delivered connection request does not mean the device will call in.
_Avoid_: Nudge, wake-up, ping

**Inform**:
A call from a device to the controller. It carries the device's current version and why it called. This is the only time the controller can give the device commands.
_Avoid_: Heartbeat, check-in, session

**Command**:
An instruction returned in the response to an inform. The only command is Download.

**Command Key**:
The unique id of a Download command. The device repeats it in its acknowledgement and in its TransferComplete. It is how the controller matches the result to the command across the reboot.
_Avoid_: Correlation id, request id

**Acknowledgement**:
The device saying it accepted or rejected a command. It does not say whether the upgrade worked.
_Avoid_: Command response, ack

**TransferComplete**:
The device reporting the final result of a Download, with its command key and a fault code (0 means success).
_Avoid_: TC, completion

**Duplicate Callback**:
An inform or acknowledgement that repeats one the controller has already processed. It is accepted and has no effect.

