package org.moqui.device.gateway;

import java.util.HashMap;
import java.util.Map;

public class GatewayStartupDiscoveryTestProfile extends LocalNoInfraTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("gateway.device.id", "GW_EDGE_01");
        return overrides;
    }
}
