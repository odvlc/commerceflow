import Keycloak from "keycloak-js";

export const identity = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || "http://localhost:8180",
  realm: "commerceflow",
  clientId: "commerceflow-web",
});
export const initializeAuth = () =>
  identity.init({
    onLoad: "check-sso",
    pkceMethod: "S256",
    checkLoginIframe: false,
  });
export const login = () =>
  identity.login({ redirectUri: window.location.origin + "/" });
export const logout = () =>
  identity.logout({ redirectUri: window.location.origin + "/" });
export async function accessToken(): Promise<string | undefined> {
  if (!identity.authenticated) return undefined;
  await identity.updateToken(30);
  return identity.token;
}
export const roles = () => identity.realmAccess?.roles || [];
