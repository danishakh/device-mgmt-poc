# Use JdbcClient with conditional UPDATEs, not JPA

Every device upgrade state transition is one `UPDATE device_upgrade … WHERE id = ? AND state = ? AND version = ?`. The returned row count decides the outcome: 1 means the transition was applied, 0 means the callback was a duplicate or the request lost a race. That count is how we handle duplicates (requirement 3) and how we count metrics only when a row changed.

JPA can run the same SQL through `@Modifying @Query`. It also adds a persistence context. That context can hold stale entities after a bulk update, and dirty checking can silently overwrite a conditional update when an entity is saved later. Using both styles on the same table invites that bug. `JdbcClient` keeps every write as visible SQL, so we use it and remove `spring-boot-starter-data-jpa`.

