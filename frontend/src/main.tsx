// Initializes identity before mounting private views and surfaces setup failures in the app.
import {createRoot} from "react-dom/client";
import App from "./App";
import {initializeAuth} from "./auth";
import "./styles.css";

let initialError = "";
try {
    // Resolve the Keycloak session before React renders private panels.
    await initializeAuth();
} catch {
    initialError =
        "Sign-in is temporarily unavailable. You can still browse the collection.";
}
createRoot(document.getElementById("root")!).render(
    <App initialError={initialError}/>,
);
