import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { SWRConfig } from 'swr';
import { HomePage } from './pages/HomePage';
import { BrowserPage } from './pages/BrowserPage';

function App() {
  return (
    <SWRConfig
      value={{
        errorRetryCount: 3,
        dedupingInterval: 2000,
      }}
    >
      <Router>
        <div className="min-h-screen bg-gray-50">
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/browse/:repositoryId" element={<BrowserPage />} />
            <Route path="/browse/:repositoryId/*" element={<BrowserPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </Router>
    </SWRConfig>
  );
}

export default App;