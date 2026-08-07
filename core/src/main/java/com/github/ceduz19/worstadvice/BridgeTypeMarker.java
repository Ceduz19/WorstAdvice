package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.bootstrap.BootstrapBridge;

/**
 * Its field descriptor is read from bytecode to discover the relocated bridge
 * name without loading the bridge through the application class loader.
 */
final class BridgeTypeMarker {
    @SuppressWarnings("unused")
    private BootstrapBridge bridge;
}
