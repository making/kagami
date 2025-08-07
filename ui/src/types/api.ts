// API response types based on the Kagami API documentation

export interface RepositoryInfo {
  id: string;
  url: string;
  artifactCount: number;
  totalSize: number;
  lastUpdated: string | null;
}

export interface RepositoryListResponse {
  repositories: RepositoryInfo[];
}

export interface RepositoryEntry {
  name: string;
  type: 'file' | 'directory';
  path: string;
  size?: number; // Only present for files
  lastModified: string;
}

export interface BrowseResult {
  repositoryId: string;
  currentPath: string;
  parentPath: string | null;
  entries: RepositoryEntry[];
}

export interface FileInfo {
  repositoryId: string;
  path: string;
  name: string;
  type: 'file';
  size: number;
  lastModified: string;
  contentType: string;
  sha1?: string;
  sha256?: string;
}

export interface ApiError {
  message?: string;
  status: number;
}