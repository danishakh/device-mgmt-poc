# The database is the only state

The controller must survive a restart in the middle of a campaign (requirement 8). So there are no in-memory queues, semaphores or counters that affect behaviour:
- `maxConcurrent` is enforced by counting in-flight `device_upgrade` rows.
- Campaign progress is a `GROUP BY state` query.
- Every wait stores an absolute `deadline_at`.

The first scheduler tick after a restart expires overdue rows and fills free slots. A connection request lost in a crash is covered by the Notified retry. Connection requests are safe to repeat.

Micrometer counters (requests sent, errors) are the one exception. They reset on restart because they report activity, not state. State gauges are read from the database, so they do not reset.

