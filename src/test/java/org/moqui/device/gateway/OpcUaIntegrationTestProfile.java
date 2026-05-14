package org.moqui.device.gateway;

import java.util.HashMap;
import java.util.Map;

public class OpcUaIntegrationTestProfile extends IntegrationTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("mqtt.read.route.autoStartup", "false");
        overrides.put("mqtt.read.consume.uri", "seda:mqtt-read-device-request-in");
        return overrides;
    }
}
