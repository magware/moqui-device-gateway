package org.moqui.device.gateway;

import java.util.HashMap;
import java.util.Map;

public class GatewayRequestScopeValidationTestProfile extends GatewayStartupDiscoveryTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("gateway.api.auth.enabled", "false");
        return overrides;
    }
}
