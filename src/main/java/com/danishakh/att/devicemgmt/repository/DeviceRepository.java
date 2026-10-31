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
     * Records the devices the fleet listed, in one statement. Known devices are left alone.
     *
     * @param devices the devices the fleet listed
     * @param now the time to record as first seen and last updated
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
     * Records the latest facts from an inform. An unknown device gets its row here.
     *
     * @param deviceId the device sending the inform
     * @param firmwareVersion the firmware version the device reported
     * @param event the inform event the device sent
     * @param now the time of the inform
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
