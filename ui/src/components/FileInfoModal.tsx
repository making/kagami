import { useFileInfo } from '../hooks/useApi';
import { Card, CardHeader, CardTitle, CardContent } from './ui/Card';
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
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center p-4 z-50">
      <Card className="w-full max-w-2xl max-h-[90vh] overflow-y-auto">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-lg font-semibold truncate pr-4">
            File Information: {entry.name}
          </CardTitle>
          <Button variant="ghost" size="sm" onClick={onClose}>
            <X className="h-4 w-4" />
          </Button>
        </CardHeader>
        
        <CardContent className="space-y-4">
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
            <div className="space-y-4">
              {/* Basic Information */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="text-sm font-medium text-gray-700">File Name</label>
                  <div className="mt-1 text-sm text-gray-900 font-mono bg-gray-50 p-2 rounded">
                    {fileInfo.name}
                  </div>
                </div>
                
                <div>
                  <label className="text-sm font-medium text-gray-700">Content Type</label>
                  <div className="mt-1 text-sm text-gray-900 bg-gray-50 p-2 rounded">
                    {fileInfo.contentType}
                  </div>
                </div>
                
                <div>
                  <label className="text-sm font-medium text-gray-700">File Size</label>
                  <div className="mt-1 text-sm text-gray-900 bg-gray-50 p-2 rounded">
                    {formatFileSize(fileInfo.size)}
                  </div>
                </div>
                
                <div>
                  <label className="text-sm font-medium text-gray-700">Last Modified</label>
                  <div className="mt-1 text-sm text-gray-900 bg-gray-50 p-2 rounded">
                    {formatDate(fileInfo.lastModified)}
                  </div>
                </div>
              </div>

              {/* Full Path */}
              <div>
                <label className="text-sm font-medium text-gray-700">Path</label>
                <div className="mt-1 flex items-center space-x-2">
                  <div className="flex-1 text-sm text-gray-900 font-mono bg-gray-50 p-2 rounded">
                    {fileInfo.path}
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleCopy(fileInfo.path, 'path')}
                  >
                    {copiedField === 'path' ? (
                      <Check className="h-4 w-4 text-green-600" />
                    ) : (
                      <Copy className="h-4 w-4" />
                    )}
                  </Button>
                </div>
              </div>

              {/* Checksums */}
              {(fileInfo.sha1 || fileInfo.sha256) && (
                <div className="space-y-3">
                  <h3 className="text-sm font-medium text-gray-700">Checksums</h3>
                  
                  {fileInfo.sha1 && (
                    <div>
                      <label className="text-xs font-medium text-gray-600">SHA-1</label>
                      <div className="mt-1 flex items-center space-x-2">
                        <div className="flex-1 text-sm text-gray-900 font-mono bg-gray-50 p-2 rounded break-all">
                          {fileInfo.sha1}
                        </div>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleCopy(fileInfo.sha1!, 'sha1')}
                        >
                          {copiedField === 'sha1' ? (
                            <Check className="h-4 w-4 text-green-600" />
                          ) : (
                            <Copy className="h-4 w-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                  )}
                  
                  {fileInfo.sha256 && (
                    <div>
                      <label className="text-xs font-medium text-gray-600">SHA-256</label>
                      <div className="mt-1 flex items-center space-x-2">
                        <div className="flex-1 text-sm text-gray-900 font-mono bg-gray-50 p-2 rounded break-all">
                          {fileInfo.sha256}
                        </div>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleCopy(fileInfo.sha256!, 'sha256')}
                        >
                          {copiedField === 'sha256' ? (
                            <Check className="h-4 w-4 text-green-600" />
                          ) : (
                            <Copy className="h-4 w-4" />
                          )}
                        </Button>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Actions */}
              <div className="flex justify-end space-x-2 pt-4">
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
        </CardContent>
      </Card>
    </div>
  );
}