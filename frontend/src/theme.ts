import { createTheme, alpha } from "@mui/material/styles";

// Material Design 3 inspired palette. Compact ("small"/"medium") component
// density is applied via component defaultProps below so the whole app feels
// tight and information-dense without repeating size props everywhere.
const PRIMARY = "#2f6bff";
const SECONDARY = "#5b6472";

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: PRIMARY, dark: "#2356d8" },
    secondary: { main: SECONDARY },
    success: { main: "#1aa564" },
    error: { main: "#e0413f" },
    warning: { main: "#c9760a" },
    info: { main: PRIMARY },
    background: { default: "#eef1f6", paper: "#ffffff" },
    text: { primary: "#1d2430", secondary: "#5a6573" },
    divider: "#dbe1ea",
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: [
      "Roboto",
      "-apple-system",
      "BlinkMacSystemFont",
      "Segoe UI",
      "Helvetica",
      "Arial",
      "sans-serif",
    ].join(","),
    fontSize: 13,
    button: { textTransform: "none", fontWeight: 600, letterSpacing: 0.2 },
    h6: { fontWeight: 700 },
    subtitle2: { fontWeight: 700 },
  },
  components: {
    MuiButton: {
      defaultProps: { size: "small", disableElevation: true },
      styleOverrides: { root: { borderRadius: 999 } },
    },
    MuiButtonGroup: { defaultProps: { size: "small" } },
    MuiTextField: { defaultProps: { size: "small" } },
    MuiFormControl: { defaultProps: { size: "small" } },
    MuiInputLabel: {
      styleOverrides: {
        // Bold field legends consistently across every screen.
        root: { fontWeight: 600 },
      },
    },
    MuiSelect: { defaultProps: { size: "small" } },
    MuiInputBase: { defaultProps: { size: "small" } },
    MuiCheckbox: { defaultProps: { size: "small" } },
    MuiChip: { defaultProps: { size: "small" } },
    MuiIconButton: { defaultProps: { size: "small" } },
    MuiTable: { defaultProps: { size: "small" } },
    MuiToggleButtonGroup: { defaultProps: { size: "small" } },
    MuiTooltip: { defaultProps: { arrow: true } },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: "none" },
        outlined: { borderColor: "#dbe1ea" },
      },
    },
    MuiCard: {
      defaultProps: { variant: "outlined" },
      styleOverrides: { root: { borderRadius: 14 } },
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: "default" },
      styleOverrides: {
        root: {
          backgroundColor: alpha("#ffffff", 0.9),
          backdropFilter: "blur(8px)",
          borderBottom: "1px solid #dbe1ea",
          color: "#1d2430",
        },
      },
    },
  },
});
