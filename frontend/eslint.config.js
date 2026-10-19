// Applies TypeScript checks to application source while allowing intentionally unused underscore-
// prefixed arguments.
import tseslint from "typescript-eslint";

export default tseslint.config(...tseslint.configs.recommended, {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
        // Underscore-prefixed callback parameters are intentionally ignored; other unused values remain
        // errors.
        "@typescript-eslint/no-unused-vars": ["error", {argsIgnorePattern: "^_"}],
    },
});
