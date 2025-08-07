import useSWR from 'swr';
import type { RepositoryListResponse, BrowseResult, FileInfo } from '../types/api';

const fetcher = async (url: string) => {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }
  return response.json();
};

export function useRepositories() {
  const { data, error, isLoading } = useSWR<RepositoryListResponse>(
    '/repositories',
    fetcher
  );

  return {
    repositories: data?.repositories || [],
    isLoading,
    error,
  };
}

export function useBrowseRepository(repositoryId: string | null, path?: string) {
  const url = repositoryId 
    ? `/repositories/${encodeURIComponent(repositoryId)}/browse${path ? `?path=${encodeURIComponent(path)}` : ''}`
    : null;

  const { data, error, isLoading } = useSWR<BrowseResult>(url, fetcher);

  return {
    result: data,
    isLoading,
    error,
  };
}

export function useFileInfo(repositoryId: string | null, path: string | null) {
  const url = repositoryId && path 
    ? `/repositories/${encodeURIComponent(repositoryId)}/info?path=${encodeURIComponent(path)}`
    : null;

  const { data, error, isLoading } = useSWR<FileInfo>(url, fetcher);

  return {
    fileInfo: data,
    isLoading,
    error,
  };
}