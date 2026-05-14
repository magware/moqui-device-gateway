package org.moqui.device.gateway;

import javax.sql.DataSource;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Registers the Quarkus default DataSource in the Camel registry under the name
 * configured by {@code camel.sql.datasource} (default: {@code moquiDataSource}).
 *
 * This keeps the standalone gateway routes and beans aligned around the same Camel
 * registry name for JDBC access:
 *   sql:{{query}}?dataSource=#{{camel.sql.datasource}}
 *
 * We inject {@code AgroalDataSource} (the Quarkus-specific subtype) rather than
 * plain {@code DataSource} to avoid CDI ambiguity: our {@code @Produces @Named} method
 * would otherwise create a second {@code @Default DataSource} bean alongside the
 * synthetic Agroal bean, causing a {@code AmbiguousResolutionException} at boot.
 */
@ApplicationScoped
public class CamelRegistryProducer {

    @Inject
    AgroalDataSource agroalDataSource;

    @Produces
    @Named("moquiDataSource")
    DataSource moquiDataSource() {
        return agroalDataSource;
    }
}
