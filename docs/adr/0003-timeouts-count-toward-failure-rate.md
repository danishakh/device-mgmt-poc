# Timed-out device upgrades count toward the failure rate

The failure rate is (Failed + Timed Out) / Finished, and it is evaluated only after a minimum sample of finished device upgrades (default 50). Cancelled device upgrades never started, so they are not counted. Timed Out includes offline devices that never answer a connection request. There is no flag to exclude them.

This is the literal reading of "failure rate", and it protects the fleet. The cost is that a fleet with many unreachable devices can halt a campaign even when the image is fine. The campaign response shows counts by outcome reason (`NO_CALLBACK` and the others), so the operator can see why a halt happened.

