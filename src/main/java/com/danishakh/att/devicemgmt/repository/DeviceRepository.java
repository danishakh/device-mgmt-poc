package com.danishakh.att.devicemgmt.repository;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import com.danishakh.att.devicemgmt.statemachine.InformEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * Reads and writes the device table. It has no transactions of its own: the service that calls
 * it owns the transaction.
 */
@Repository
public class DeviceRepository {
    private final JdbcClient jdbc;
    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }
    /**
     * Records devices the fleet listed, in one statement. A device that is already known is left
     * alone, so facts it reported itself (version, last event) are never overwritten.
     */
    public void insertIfAbsent(List<FleetDevice> devices, Instant now) {
        String[] ids = devices.stream().map(FleetDevice::deviceId).toArray(String[]::new);
        String[] versions = devices.stream().map(FleetDevice::firmwareVersion).toArray(String[]::new);
        jdbc.sql("""
                INSERT INTO device (device_id, firmware_version, first_seen_at, updated_at)
                SELECT t.device_id, t.firmware_version, ?, ?
                  FROM unnest(CAST(? AS text[]), CAST(? AS text[])) AS t(device_id, firmware_version)
                ON CONFLICT (device_id) DO NOTHING""")
                .param(Timestamp.from(now))
                .param(Timestamp.from(now))
                .param(ids)
                .param(versions)
                .update();
    }

    /**
     * Records the latest facts from an inform: the version the device reported, the event, and when.
     * An unknown device gets its row here, so a device in no campaign is still known.
     */
    public void recordInform(String deviceId, String firmwareVersion, InformEvent event, Instant now) {
        jdbc.sql("""
                INSERT INTO device (device_id, firmware_version, last_event, last_inform_at, first_seen_at, updated_at)
                VALUES (:deviceId, :firmwareVersion, :lastEvent, :now, :now, :now)
                ON CONFLICT (device_id) DO UPDATE
                   SET firmware_version = EXCLUDED.firmware_version,
                       last_event = EXCLUDED.last_event,
                       last_inform_at = EXCLUDED.last_inform_at,
                       updated_at = EXCLUDED.updated_at""")
                .param("deviceId", deviceId)
                .param("firmwareVersion", firmwareVersion)
                .param("lastEvent", event.name())
                .param("now", Timestamp.from(now))
                .update();
    }
}
