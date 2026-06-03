package org.moqui.device.gateway;

import javax.sql.DataSource;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Registers both Quarkus DataSources in the Camel registry:
 *  - {@code moquiDataSource} → default datasource (transactional Moqui DB: DEVICE_REQUEST, PARAMETER, DEVICE_CONFIG, …)
 *  - {@code moquiLogDataSource} → "log" named datasource (telemetry DB: PARAMETER_LOG, DEVICE_LOG)
 *
 * SQL route URIs reference one of these by name:
 *   sql:{{query}}?dataSource=#{{camel.sql.datasource}} (transactional DB)
 *   sql:{{query}}?dataSource=#{{camel.sql.log.datasource}} (log DB)
 *
 * Both datasources can point to the same DB during testing (standalone moqui-gateway-database).
 * In production, point the default to the main Moqui DB and "log" to the separate telemetry DB.
 *
 * We inject {@code AgroalDataSource} rather than plain {@code DataSource} to avoid CDI
 * ambiguity: a {@code @Produces @Named} method on plain {@code DataSource} would create a
 * second {@code @Default} bean alongside the synthetic Agroal bean, causing
 * {@code AmbiguousResolutionException} at boot.
 */
@ApplicationScoped
public class CamelRegistryProducer {

    @Inject
    AgroalDataSource agroalDataSource;

    @Inject
    @io.quarkus.agroal.DataSource("log")
    AgroalDataSource logAgroalDataSource;

    @Produces
    @Named("moquiDataSource")
    DataSource moquiDataSource() {
        return agroalDataSource;
    }

    @Produces
    @Named("moquiLogDataSource")
    DataSource moquiLogDataSource() {
        return logAgroalDataSource;
    }
}
