package org.moqui.device.gateway;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.AddressSpace;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfigBuilder;

/**
 * Standalone Eclipse Milo OPC UA server for integration tests.
 *
 * Exposes virtual_plc nodes that mirror the gateway seed data.
 * Provides clean push/capture API without reflection.
 */
public class MiloTestServer {

    private static final String NAMESPACE_URI = "urn:moqui:test:gateway:namespace";
    static final String SERVER_PATH = "/milo";

    // NodeId i=11 = Double, i=12 = String (OPC UA built-in types)
    private static final NodeId TYPE_DOUBLE = new NodeId(0, 11);
    private static final NodeId TYPE_STRING = new NodeId(0, 12);

    private final OpcUaServer server;
    private final TestNamespace namespace;
    private final int port;

    public MiloTestServer(int port) throws Exception {
        this.port = port;

        EndpointConfig endpoint = EndpointConfig.newBuilder()
            .setBindAddress("0.0.0.0")
            .setBindPort(port)
            .setHostname("127.0.0.1")
            .setPath(SERVER_PATH)
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .addTokenPolicy(new UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null))
            .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
            .setApplicationName(LocalizedText.english("Moqui OPC UA Test Server"))
            .setApplicationUri("urn:moqui:test:opcua")
            .setProductUri("urn:moqui:test:opcua")
            .setEndpoints(Set.of(endpoint))
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .build();

        server = new OpcUaServer(config,
            profile -> new OpcTcpServerTransport(new OpcTcpServerTransportConfigBuilder().build()));

        namespace = new TestNamespace(server);
        namespace.startup();
        server.startup().get(5, TimeUnit.SECONDS);
    }

    /** Listening port. */
    public int getPort() {
        return port;
    }

    /** Numeric OPC UA namespace index of the test namespace. */
    public int getNamespaceIndex() {
        return namespace.getNamespaceIndex().intValue();
    }

    /**
     * Pushes a new value into a server node.
     * Triggers OPC UA subscription notifications to any subscribed client.
     */
    public void pushValue(String itemId, Object value) {
        namespace.pushValue(itemId, value);
    }

    /**
     * Returns the last DataValue written to a node by an OPC UA client.
     * Null if no client write has been received yet.
     */
    public DataValue getLastWrittenValue(String itemId) {
        return namespace.getLastWrittenValue(itemId);
    }

    /** Shuts down the namespace and server cleanly. */
    public void shutdown() throws Exception {
        namespace.shutdown();
        server.shutdown().get(5, TimeUnit.SECONDS);
    }

    private static class TestNamespace extends ManagedNamespaceWithLifecycle {

        private final Map<String, UaVariableNode> nodes = new ConcurrentHashMap<>();
        private final Map<String, DataValue> lastWrittenValues = new ConcurrentHashMap<>();
        private final SubscriptionModel subscriptionModel;

        TestNamespace(OpcUaServer server) {
            super(server, NAMESPACE_URI);
            subscriptionModel = new SubscriptionModel(server, this);
            getLifecycleManager().addLifecycle(subscriptionModel);
            getLifecycleManager().addStartupTask(this::createNodes);
        }

        private void createNodes() {
            addReadOnlyNode("virtual_plc_feedback", TYPE_DOUBLE, new DataValue(new Variant(0.0)));
            addReadOnlyNode("virtual_plc_fault", TYPE_STRING, new DataValue(new Variant("N")));
            addWritableNode("virtual_plc_reference_write", TYPE_DOUBLE, new DataValue(new Variant(0.0)));
        }

        private void addReadOnlyNode(String itemId, NodeId dataType, DataValue initialValue) {
            UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(new NodeId(getNamespaceIndex(), itemId))
                .setBrowseName(new QualifiedName(getNamespaceIndex(), itemId))
                .setDisplayName(LocalizedText.english(itemId))
                .setValue(initialValue)
                .setDataType(dataType)
                .setValueRank(-1)
                .setAccessLevel(AccessLevel.READ_ONLY)
                .buildAndAdd();
            nodes.put(itemId, node);
        }

        private void addWritableNode(String itemId, NodeId dataType, DataValue initialValue) {
            UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(new NodeId(getNamespaceIndex(), itemId))
                .setBrowseName(new QualifiedName(getNamespaceIndex(), itemId))
                .setDisplayName(LocalizedText.english(itemId))
                .setValue(initialValue)
                .setDataType(dataType)
                .setValueRank(-1)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .buildAndAdd();
            nodes.put(itemId, node);
        }

        void pushValue(String itemId, Object value) {
            UaVariableNode node = nodes.get(itemId);
            if (node == null) {
                throw new IllegalArgumentException("Unknown test node: " + itemId);
            }
            node.setValue(new DataValue(new Variant(value), StatusCode.GOOD, DateTime.now()));
        }

        DataValue getLastWrittenValue(String itemId) {
            return lastWrittenValues.get(itemId);
        }

        @Override
        public List<StatusCode> write(AddressSpace.WriteContext context, List<WriteValue> writeValues) {
            List<StatusCode> results = super.write(context, writeValues);
            for (int i = 0; i < writeValues.size(); i++) {
                WriteValue wv = writeValues.get(i);
                if (!AttributeId.Value.uid().equals(wv.getAttributeId())) continue;
                Object identifier = wv.getNodeId().getIdentifier();
                if (identifier instanceof String nodeKey && nodes.containsKey(nodeKey)) {
                    lastWrittenValues.put(nodeKey, wv.getValue());
                }
            }
            return results;
        }

        @Override
        public void onDataItemsCreated(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsCreated(dataItems);
        }

        @Override
        public void onDataItemsModified(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsModified(dataItems);
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsDeleted(dataItems);
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
            subscriptionModel.onMonitoringModeChanged(monitoredItems);
        }
    }
}
