import React from "react";
import ReactDOM from "react-dom/client";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import "@fontsource/roboto/latin-300.css";
import "@fontsource/roboto/latin-400.css";
import "@fontsource/roboto/latin-500.css";
import "@fontsource/roboto/latin-700.css";

import { theme } from "./theme";
import { UiProvider } from "./app/UiProvider";
import { AppStateProvider } from "./app/AppState";
import { App } from "./app/App";
import "./styles/global.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <UiProvider>
        <AppStateProvider>
          <App />
        </AppStateProvider>
      </UiProvider>
    </ThemeProvider>
  </React.StrictMode>
);
