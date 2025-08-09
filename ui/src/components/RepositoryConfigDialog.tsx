import { useState } from 'react';
import { Dialog, DialogHeader, DialogTitle, DialogClose, DialogContent } from './ui/Dialog';
import { Button } from './ui/Button';
import { Copy, Check } from 'lucide-react';
import type { RepositoryInfo } from '../types/api';
import { generateConfigExample, type BuildTool } from '../utils/configExamples';

interface RepositoryConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  repository: RepositoryInfo | null;
}


export function RepositoryConfigDialog({ open, onOpenChange, repository }: RepositoryConfigDialogProps) {
  const [selectedTool, setSelectedTool] = useState<BuildTool>('maven');
  const [copiedConfig, setCopiedConfig] = useState<string | null>(null);

  if (!repository) return null;

  const baseUrl = `${window.location.origin}/artifacts/${repository.id}`;

  const getConfiguration = (tool: BuildTool) => {
    const token = repository.isPrivate ? 'YOUR_JWT_TOKEN_HERE' : '';
    return generateConfigExample(tool, {
      repositoryIds: [repository.id],
      token,
      baseUrl: window.location.origin,
      isPrivate: repository.isPrivate
    });
  };

  const handleCopy = async (content: string, configType: string) => {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedConfig(configType);
      setTimeout(() => setCopiedConfig(null), 2000);
    } catch (error) {
      console.error('Failed to copy to clipboard:', error);
    }
  };

  const config = getConfiguration(selectedTool);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogHeader>
        <DialogTitle>
          Repository Configuration - {repository.id}
        </DialogTitle>
        <DialogClose onClick={() => onOpenChange(false)} />
      </DialogHeader>
      
      <DialogContent>
        <div className="space-y-6">
          {/* Build Tool Tabs */}
          <div className="flex space-x-1 bg-gray-100 rounded-lg p-1">
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'maven'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('maven')}
            >
              Maven
            </button>
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'gradleGroovy'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('gradleGroovy')}
            >
              Gradle (Groovy)
            </button>
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'gradleKotlin'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('gradleKotlin')}
            >
              Gradle (Kotlin)
            </button>
          </div>

          {/* Repository Information */}
          <div className="bg-gray-50 rounded-lg p-4">
            <h3 className="font-medium text-gray-900 mb-2">Repository Information</h3>
            <div className="space-y-1 text-sm text-gray-600">
              <div><span className="font-medium">ID:</span> {repository.id}</div>
              <div><span className="font-medium">URL:</span> {baseUrl}</div>
              <div><span className="font-medium">Original:</span> {repository.url}</div>
            </div>
          </div>

          {/* Configuration */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="font-medium text-gray-900">{config.title}</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleCopy(config.content, selectedTool)}
                className="text-gray-600 hover:text-red-700"
              >
                {copiedConfig === selectedTool ? (
                  <>
                    <Check className="h-4 w-4 mr-1" />
                    Copied!
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4 mr-1" />
                    Copy
                  </>
                )}
              </Button>
            </div>
            
            <div className="text-sm text-gray-600 mb-2">
              Add this configuration to your <code className="bg-gray-100 px-1 rounded">{config.filename}</code>:
            </div>
            
            <div className="relative">
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm">
                <code>{config.content}</code>
              </pre>
            </div>
          </div>

          {/* Authentication Notice for Private Repositories */}
          {repository.isPrivate && (
            <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 mb-4">
              <h4 className="font-medium text-amber-900 mb-2">🔒 Authentication Required</h4>
              <div className="text-sm text-amber-800 space-y-2">
                <p>This is a private repository that requires JWT token authentication.</p>
                <p>
                  <strong>To generate a JWT token:</strong>
                </p>
                <ol className="list-decimal list-inside space-y-1 ml-2">
                  <li>Go to the <a href="/token" className="font-medium text-amber-900 underline hover:text-amber-700">Generate Token</a> page</li>
                  <li>Select this repository ({repository.id})</li>
                  <li>Choose your required permissions (artifacts:read for downloads)</li>
                  <li>Set token expiration (recommended: 6 months or less)</li>
                  <li>Generate the token and replace <code className="bg-amber-100 px-1 rounded">YOUR_JWT_TOKEN_HERE</code> in the configuration above</li>
                </ol>
              </div>
            </div>
          )}

          {/* Usage Notes */}
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <h4 className="font-medium text-blue-900 mb-2">Usage Notes</h4>
            <ul className="text-sm text-blue-800 space-y-1">
              <li>• This Kagami server caches artifacts from the original repository</li>
              <li>• Artifacts are fetched on-demand and cached locally for faster subsequent access</li>
              <li>• You can use this as a primary repository or as a mirror for faster downloads</li>
              {repository.id === 'central' && (
                <li>• This mirrors Maven Central - you can use it as a drop-in replacement</li>
              )}
              {repository.isPrivate && (
                <li>• JWT tokens cannot be revoked - use appropriate expiration times for security</li>
              )}
            </ul>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}