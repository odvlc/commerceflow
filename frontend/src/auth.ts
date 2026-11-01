// Uses Keycloak PKCE and refreshes browser tokens in memory before API calls.
import Keycloak from "keycloak-js";

export const identity = new Keycloak({
    url: import.meta.env.VITE_KEYCLOAK_URL || "http://localhost:8180",
    realm: "commerceflow",
    clientId: "commerceflow-web",
});
export const initializeAuth = () =>
    identity.init({
        onLoad: "check-sso",
        // PKCE binds the authorization code to this login attempt; browser clients have no client secret.
        pkceMethod: "S256",
        checkLoginIframe: false,
    });
export const login = () =>
    identity.login({redirectUri: window.location.origin + "/"});
export const logout = () =>
    identity.logout({redirectUri: window.location.origin + "/"});

export async function accessToken(): Promise<string | undefined> {
    if (!identity.authenticated) return undefined;
    // Refresh tokens with less than 30 seconds remaining. Propagate refresh failures instead of
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
    // Hide private views if refresh fails so an expired session cannot retain access.
    identity.clearToken();
    notifySession();
};
