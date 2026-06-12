import { useFileInfo } from '../hooks/useApi';
import { Button } from './ui/Button';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
import { X, Download, Copy, Check } from 'lucide-react';
import { formatFileSize, formatDate } from '../utils/format';
import { useState } from 'react';
import type { RepositoryEntry } from '../types/api';

interface FileInfoModalProps {
  repositoryId: string;
  entry: RepositoryEntry;
  onClose: () => void;
}

export function FileInfoModal({ repositoryId, entry, onClose }: FileInfoModalProps) {
  const { fileInfo, isLoading, error } = useFileInfo(repositoryId, entry.path);
  const [copiedField, setCopiedField] = useState<string | null>(null);

  const handleCopy = async (text: string, field: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedField(field);
      setTimeout(() => setCopiedField(null), 2000);
    } catch (err) {
      console.error('Failed to copy to clipboard:', err);
    }
  };

  const handleDownload = () => {
    const downloadUrl = `/artifacts/${repositoryId}/${entry.path}`;
    window.open(downloadUrl, '_blank');
  };

  return (
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
      <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto border border-line bg-paper shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b-2 border-b-ink">
          <h2 className="registry-label font-semibold truncate pr-4 flex items-center gap-3 before:content-[''] before:w-[9px] before:h-[9px] before:shrink-0 before:bg-gradient-to-r before:from-accent before:to-magenta">
            File Information / {entry.name}
          </h2>
          <button
            onClick={onClose}
            className="p-2 text-ink-3 hover:text-white hover:bg-ink transition-colors cursor-pointer"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="p-6 space-y-5">
          {isLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner size="lg" />
            </div>
          ) : error ? (
            <Alert variant="error">
              <AlertDescription>
                Failed to load file information: {error.message}
              </AlertDescription>
            </Alert>
          ) : fileInfo ? (
            <div className="space-y-5">
              {/* Basic Information */}
              <div className="grid grid-cols-1 md:grid-cols-2 border border-line">
                <div className="border-b md:border-r border-line p-4">
                  <div className="registry-label text-ink-3 mb-1.5">File Name</div>
                  <div className="text-[13px] break-all">{fileInfo.name}</div>
                </div>
                <div className="border-b border-line p-4">
                  <div className="registry-label text-ink-3 mb-1.5">Content Type</div>
                  <div className="text-[13px]">{fileInfo.contentType}</div>
                </div>
                <div className="border-b md:border-b-0 md:border-r border-line p-4">
                  <div className="registry-label text-ink-3 mb-1.5">File Size</div>
                  <div className="text-[13px]">{formatFileSize(fileInfo.size)}</div>
                </div>
                <div className="p-4">
                  <div className="registry-label text-ink-3 mb-1.5">Last Modified</div>
                  <div className="text-[13px]">{formatDate(fileInfo.lastModified)}</div>
                </div>
              </div>

              {/* Full Path */}
              <div>
                <div className="registry-label text-ink-3 mb-1.5">Path</div>
                <div className="flex items-stretch">
                  <div className="flex-1 text-[12px] border border-line bg-wash p-2.5 break-all">
                    {fileInfo.path}
                  </div>
                  <button
                    className="border border-l-0 border-line px-3 cursor-pointer text-ink-2 hover:bg-ink hover:text-white transition-colors"
                    onClick={() => handleCopy(fileInfo.path, 'path')}
                  >
                    {copiedField === 'path' ? (
                      <Check className="h-4 w-4 text-green-600" />
                    ) : (
                      <Copy className="h-4 w-4" />
                    )}
                  </button>
                </div>
              </div>

              {/* Checksums */}
              {(fileInfo.sha1 || fileInfo.sha256) && (
                <div className="space-y-3">
                  <h3 className="registry-label font-semibold">Checksums</h3>

                  {fileInfo.sha1 && (
                    <div>
                      <div className="registry-label text-ink-3 mb-1.5">SHA-1</div>
                      <div className="flex items-stretch">
                        <div className="flex-1 text-[12px] border border-line bg-wash p-2.5 break-all">
                          {fileInfo.sha1}
                        </div>
                        <button
                          className="border border-l-0 border-line px-3 cursor-pointer text-ink-2 hover:bg-ink hover:text-white transition-colors"
                          onClick={() => handleCopy(fileInfo.sha1!, 'sha1')}
                        >
                          {copiedField === 'sha1' ? (
                            <Check className="h-4 w-4 text-green-600" />
                          ) : (
                            <Copy className="h-4 w-4" />
                          )}
                        </button>
                      </div>
                    </div>
                  )}

                  {fileInfo.sha256 && (
                    <div>
                      <div className="registry-label text-ink-3 mb-1.5">SHA-256</div>
                      <div className="flex items-stretch">
                        <div className="flex-1 text-[12px] border border-line bg-wash p-2.5 break-all">
                          {fileInfo.sha256}
                        </div>
                        <button
                          className="border border-l-0 border-line px-3 cursor-pointer text-ink-2 hover:bg-ink hover:text-white transition-colors"
                          onClick={() => handleCopy(fileInfo.sha256!, 'sha256')}
                        >
                          {copiedField === 'sha256' ? (
                            <Check className="h-4 w-4 text-green-600" />
                          ) : (
                            <Copy className="h-4 w-4" />
                          )}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Actions */}
              <div className="flex justify-end space-x-2 pt-2">
                <Button variant="secondary" onClick={onClose}>
                  Close
                </Button>
                <Button onClick={handleDownload} className="flex items-center space-x-2">
                  <Download className="h-4 w-4" />
                  <span>Download</span>
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
