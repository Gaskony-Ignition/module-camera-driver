package com.gaskony.camera.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CameraDriverPaths.
 * Tests that all path constants are correctly defined and self-consistent.
 */
class CameraDriverPathsTest {

    // ==================== Constructor Test ====================

    @Test
    void testConstructor_ThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> {
            var constructor = CameraDriverPaths.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        })
        .hasCauseInstanceOf(UnsupportedOperationException.class)
        .getCause()
        .hasMessageContaining("CameraDriverPaths is a constants class");
    }

    // ==================== MOUNT_ALIAS Tests ====================

    @Test
    void testMountAlias_Value() {
        assertThat(CameraDriverPaths.MOUNT_ALIAS).isEqualTo("camera-driver");
    }

    @Test
    void testMountAlias_NotNullOrEmpty() {
        assertThat(CameraDriverPaths.MOUNT_ALIAS).isNotNull().isNotEmpty();
    }

    // ==================== DATA_BASE Tests ====================

    @Test
    void testDataBase_Value() {
        assertThat(CameraDriverPaths.DATA_BASE).isEqualTo("/data/camera-driver");
    }

    @Test
    void testDataBase_StartsWithSlashData() {
        assertThat(CameraDriverPaths.DATA_BASE).startsWith("/data/");
    }

    @Test
    void testDataBase_EndsWithMountAlias() {
        assertThat(CameraDriverPaths.DATA_BASE).endsWith(CameraDriverPaths.MOUNT_ALIAS);
    }

    @Test
    void testDataBase_ContainsMountAlias() {
        assertThat(CameraDriverPaths.DATA_BASE).contains(CameraDriverPaths.MOUNT_ALIAS);
    }

    @Test
    void testDataBase_ConsistentWithMountAlias() {
        // DATA_BASE must be exactly "/data/" + MOUNT_ALIAS — nothing more, nothing less
        assertThat(CameraDriverPaths.DATA_BASE).isEqualTo("/data/" + CameraDriverPaths.MOUNT_ALIAS);
    }

    // ==================== Individual ROUTE_* Tests ====================

    @Test
    void testRouteSnapshot_Value() {
        assertThat(CameraDriverPaths.ROUTE_SNAPSHOT).isEqualTo("/snapshot");
    }

    @Test
    void testRouteStream_Value() {
        assertThat(CameraDriverPaths.ROUTE_STREAM).isEqualTo("/stream");
    }

    @Test
    void testRouteDevices_Value() {
        assertThat(CameraDriverPaths.ROUTE_DEVICES).isEqualTo("/devices");
    }

    @Test
    void testRouteDeviceStatus_Value() {
        assertThat(CameraDriverPaths.ROUTE_DEVICE_STATUS).isEqualTo("/device/:name/status");
    }

    @Test
    void testRouteConnectionBrowser_Value() {
        assertThat(CameraDriverPaths.ROUTE_CONNECTION_BROWSER).isEqualTo("/connection-browser");
    }

    @Test
    void testRouteHealth_Value() {
        assertThat(CameraDriverPaths.ROUTE_HEALTH).isEqualTo("/health");
    }

    @Test
    void testRouteDiagnostics_Value() {
        assertThat(CameraDriverPaths.ROUTE_DIAGNOSTICS).isEqualTo("/diagnostics");
    }

    @Test
    void testRoutePlayer_Value() {
        assertThat(CameraDriverPaths.ROUTE_PLAYER).isEqualTo("/player");
    }

    @Test
    void testRouteAuthStatus_Value() {
        assertThat(CameraDriverPaths.ROUTE_AUTH_STATUS).isEqualTo("/auth-status");
    }

    @Test
    void testRouteLogsGateway_Value() {
        assertThat(CameraDriverPaths.ROUTE_LOGS_GATEWAY).isEqualTo("/logs/gateway");
    }

    @Test
    void testRouteMsePlayerJs_Value() {
        assertThat(CameraDriverPaths.ROUTE_MSE_PLAYER_JS).isEqualTo("/mse-player.js");
    }

    // ==================== ROUTE_* General Contract Tests ====================

    @Test
    void testAllRoutes_StartWithSlash() {
        assertThat(CameraDriverPaths.ROUTE_SNAPSHOT).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_STREAM).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_DEVICES).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_DEVICE_STATUS).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_CONNECTION_BROWSER).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_HEALTH).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_DIAGNOSTICS).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_PLAYER).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_AUTH_STATUS).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_LOGS_GATEWAY).startsWith("/");
        assertThat(CameraDriverPaths.ROUTE_MSE_PLAYER_JS).startsWith("/");
    }

    @Test
    void testAllRoutes_NotNullOrEmpty() {
        assertThat(CameraDriverPaths.ROUTE_SNAPSHOT).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_STREAM).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_DEVICES).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_DEVICE_STATUS).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_CONNECTION_BROWSER).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_HEALTH).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_DIAGNOSTICS).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_PLAYER).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_AUTH_STATUS).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_LOGS_GATEWAY).isNotNull().isNotEmpty();
        assertThat(CameraDriverPaths.ROUTE_MSE_PLAYER_JS).isNotNull().isNotEmpty();
    }

    @Test
    void testAllRoutes_DoNotContainDataBase() {
        // Routes are relative segments — they must NOT include the DATA_BASE prefix.
        // Full URLs are assembled by the router as DATA_BASE + ROUTE_*
        assertThat(CameraDriverPaths.ROUTE_SNAPSHOT).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_STREAM).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_DEVICES).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_DEVICE_STATUS).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_CONNECTION_BROWSER).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_HEALTH).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_DIAGNOSTICS).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_PLAYER).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_AUTH_STATUS).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_LOGS_GATEWAY).doesNotContain(CameraDriverPaths.DATA_BASE);
        assertThat(CameraDriverPaths.ROUTE_MSE_PLAYER_JS).doesNotContain(CameraDriverPaths.DATA_BASE);
    }

    @Test
    void testFullPaths_DataBaseWithRoutes_AreWellFormed() {
        // Verify that DATA_BASE + each route produces a valid absolute path string
        String[] routes = {
            CameraDriverPaths.ROUTE_SNAPSHOT,
            CameraDriverPaths.ROUTE_STREAM,
            CameraDriverPaths.ROUTE_DEVICES,
            CameraDriverPaths.ROUTE_DEVICE_STATUS,
            CameraDriverPaths.ROUTE_CONNECTION_BROWSER,
            CameraDriverPaths.ROUTE_HEALTH,
            CameraDriverPaths.ROUTE_DIAGNOSTICS,
            CameraDriverPaths.ROUTE_PLAYER,
            CameraDriverPaths.ROUTE_AUTH_STATUS,
            CameraDriverPaths.ROUTE_LOGS_GATEWAY,
            CameraDriverPaths.ROUTE_MSE_PLAYER_JS,
        };
        for (String route : routes) {
            String fullPath = CameraDriverPaths.DATA_BASE + route;
            assertThat(fullPath)
                .as("Full path for route '%s'", route)
                .startsWith("/data/camera-driver/")
                .doesNotContain("//");
        }
    }

    @Test
    void testRouteDeviceStatus_ContainsPathParameter() {
        // ROUTE_DEVICE_STATUS uses a named path parameter :name
        assertThat(CameraDriverPaths.ROUTE_DEVICE_STATUS).contains(":name");
    }
}
