// Initializes identity before mounting private views; authentication setup failure is surfaced in
// the application.
import {createRoot} from "react-dom/client";
import App from "./App";
import {initializeAuth} from "./auth";
import "./styles.css";

let initialError = "";
try {
    // Resolve the existing Keycloak session before React decides which private panels are available.
    await initializeAuth();
} catch {
    initialError =
        "Sign-in is temporarily unavailable. You can still browse the collection.";
}
createRoot(document.getElementById("root")!).render(
    <App initialError={initialError}/>,
);
