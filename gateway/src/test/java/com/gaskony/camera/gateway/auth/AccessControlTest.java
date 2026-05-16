package com.gaskony.camera.gateway.auth;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccessControl} — the P3-CD shared access-control helper.
 *
 * <p>Pattern source: PLC Emulator's {@code GatewayAuthHelper.requireAuthentication}.
 * See /modules/.review/FINAL_REVIEW.md §5 P3 for the rationale.</p>
 */
@ExtendWith(MockitoExtension.class)
class AccessControlTest {

    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletResponse response;

    // -----------------------------------------------------------------------
    // requireAuthenticated
    // -----------------------------------------------------------------------

    @Test
    void testRequireAuthenticated_Authenticated_ReturnsTrue_NoErrorWritten() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);

        boolean result = AccessControl.requireAuthenticated(authManager, requestContext, response);

        assertThat(result).isTrue();
        verify(response, never()).sendError(eq(401), anyString());
    }

    @Test
    void testRequireAuthenticated_Unauthenticated_ReturnsFalse_Writes401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);
        when(response.isCommitted()).thenReturn(false);

        boolean result = AccessControl.requireAuthenticated(authManager, requestContext, response);

        assertThat(result).isFalse();
        verify(response).sendError(eq(401), anyString());
    }

    @Test
    void testRequireAuthenticated_NullAuthManager_FailsClosed() throws Exception {
        when(response.isCommitted()).thenReturn(false);

        // Pass null for the auth manager — must fail closed (return false +
        // 401), never quietly allow the request through.
        boolean result = AccessControl.requireAuthenticated(null, requestContext, response);

        assertThat(result).isFalse();
        verify(response).sendError(eq(401), anyString());
    }

    @Test
    void testRequireAuthenticated_ResponseAlreadyCommitted_DoesNotDoubleWrite() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);
        when(response.isCommitted()).thenReturn(true);

        boolean result = AccessControl.requireAuthenticated(authManager, requestContext, response);

        assertThat(result).isFalse();
        verify(response, never()).sendError(eq(401), anyString());
    }

    // -----------------------------------------------------------------------
    // requireAdministrator (currently aliases to requireAuthenticated until
    // role gating lands — see TODO in AccessControl.java)
    // -----------------------------------------------------------------------

    @Test
    void testRequireAdministrator_Authenticated_ReturnsTrue() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);

        boolean result = AccessControl.requireAdministrator(authManager, requestContext, response);

        assertThat(result).isTrue();
    }

    @Test
    void testRequireAdministrator_Unauthenticated_ReturnsFalse_Writes401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);
        when(response.isCommitted()).thenReturn(false);

        boolean result = AccessControl.requireAdministrator(authManager, requestContext, response);

        assertThat(result).isFalse();
        verify(response).sendError(eq(401), anyString());
    }
}
