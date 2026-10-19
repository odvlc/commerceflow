// Uses Keycloak PKCE for the browser public client and refreshes tokens in memory before API
// calls.
import Keycloak from "keycloak-js";

export const identity = new Keycloak({
    url: import.meta.env.VITE_KEYCLOAK_URL || "http://localhost:8180",
    realm: "commerceflow",
    clientId: "commerceflow-web",
});
export const initializeAuth = () =>
    identity.init({
        onLoad: "check-sso",
        // The public browser client has no client secret; PKCE binds the authorization code to this
        // login attempt.
        pkceMethod: "S256",
        checkLoginIframe: false,
    });
export const login = () =>
    identity.login({redirectUri: window.location.origin + "/"});
export const logout = () =>
    identity.logout({redirectUri: window.location.origin + "/"});

export async function accessToken(): Promise<string | undefined> {
    if (!identity.authenticated) return undefined;
    // Refresh before dispatch when less than 30 seconds remain; let failure propagate instead of
    // sending a stale token.
    await identity.updateToken(30);
    return identity.token;
}

export const roles = () => identity.realmAccess?.roles || [];

// Keycloak callbacks notify React immediately so private views unmount on session changes.
const notifySession = () => window.dispatchEvent(new Event("commerceflow-session"));
identity.onAuthSuccess = notifySession;
identity.onAuthRefreshSuccess = notifySession;
identity.onAuthLogout = notifySession;
identity.onAuthRefreshError = () => {
    // Withdraw private views immediately on refresh failure rather than keeping authority from an old
    // session.
    identity.clearToken();
    notifySession();
};
