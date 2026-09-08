package com.sunmoon.platform.transport.http;

import java.util.Map;

/**
 * One HTTP listener inside the single Core Runtime: its own port, its own
 * route table, and whether it speaks WebSocket.
 *
 * <p>Ports separate <em>exposure boundaries</em>, not processes — this is
 * still one JVM, one pair of event-loop groups (docs/adr/0002). BO gets
 * its own port so it's the only surface that needs to be published when
 * deployed (docs/adr/0007); the Order API stays on a different port.
 */
public record HttpListenerSpec(String name, int port, Map<RouteKey, RestEndpoint> routes, boolean webSocketEnabled) {
}
