import { 
  Folder, 
  FileText, 
  Package, 
  FileCode, 
  Shield,
  Hash,
  File
} from 'lucide-react';

interface FileIconProps {
  fileName: string;
  type: 'file' | 'directory';
  className?: string;
}

export function FileIcon({ fileName, type, className = "h-4 w-4" }: FileIconProps) {
  if (type === 'directory') {
    return <Folder className={`${className} text-blue-500`} />;
  }

  // Determine icon based on file extension
  const extension = fileName.split('.').pop()?.toLowerCase();
  
  switch (extension) {
    case 'jar':
      return <Package className={`${className} text-orange-500`} />;
    case 'pom':
    case 'xml':
      return <FileCode className={`${className} text-green-500`} />;
    case 'sha1':
    case 'sha256':
    case 'md5':
      return <Hash className={`${className} text-gray-500`} />;
    case 'asc':
      return <Shield className={`${className} text-purple-500`} />;
    case 'txt':
    case 'md':
      return <FileText className={`${className} text-gray-600`} />;
    default:
      return <File className={`${className} text-gray-500`} />;
  }
}