import { Route, Routes } from "react-router-dom";
import { OrgPickerPage } from "./routes/OrgPickerPage";
import { DashboardPage } from "./routes/DashboardPage";
import { DocumentDetailPage } from "./routes/DocumentDetailPage";
import { NotFoundPage } from "./routes/NotFoundPage";

export function App() {
  return (
    <Routes>
      <Route path="/" element={<OrgPickerPage />} />
      <Route path="/org/:orgId/dashboard" element={<DashboardPage />} />
      <Route path="/documents/:documentId" element={<DocumentDetailPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
