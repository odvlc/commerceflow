import { createRoot } from "react-dom/client";
import App from "./App";
import { initializeAuth } from "./auth";
import "./styles.css";

let initialError = "";
try {
  await initializeAuth();
} catch {
  initialError =
    "Sign-in is temporarily unavailable. You can still browse the collection.";
}
createRoot(document.getElementById("root")!).render(
  <App initialError={initialError} />,
);
