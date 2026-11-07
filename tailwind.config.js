/** StreamBox design tokens (Obsidian Glow) - the Tailwind CLI builds
    tailwind.css from index.html so the site ships one static stylesheet
    instead of the runtime Play CDN (faster boot on TV boxes, no flash of
    unstyled content while the CDN JIT compiles). Values match the native
    Theme.kt palette one-to-one. */
module.exports = {
  content: ["./index.html"],
  theme: {
    extend: {
      colors: {
        teal: { 200: "#7DF5DC", 300: "#3AF0C8", 400: "#00F0B5", 500: "#00F0B5", 600: "#00D6A2", 700: "#00B88A" },
        cyan: { 300: "#67E5FF", 400: "#00D2FF", 500: "#00D2FF", 600: "#00B8E6" },
        amber: { 400: "#F5A623", 500: "#F5A623", 600: "#D98C12" },
        gray: { 200: "#EAF2FF", 300: "#C7D5E8", 400: "#8E9CAE", 500: "#7A8798", 600: "#5C6B80" },
      },
      fontFamily: {
        sans: ["Plus Jakarta Sans", "Inter", "Segoe UI", "sans-serif"],
        display: ["Space Grotesk", "sans-serif"],
      },
    },
  },
};
