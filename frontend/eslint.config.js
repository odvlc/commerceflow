// Lints application TypeScript and ignores unused arguments that start with an underscore.
import tseslint from "typescript-eslint";

export default tseslint.config(...tseslint.configs.recommended, {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
        // Other unused values remain errors.
        "@typescript-eslint/no-unused-vars": ["error", {argsIgnorePattern: "^_"}],
    },
});
