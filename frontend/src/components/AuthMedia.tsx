import { useAuthMedia } from '@/hooks/useAuthMedia';
import { Loader2 } from 'lucide-react';

export function AuthImage({
  src,
  alt,
  className,
  loading,
}: {
  src: string | null;
  alt: string;
  className?: string;
  loading?: 'lazy' | 'eager';
}) {
  const { url, loading: fetching, error } = useAuthMedia(src);

  if (fetching) {
    return (
      <div className={`flex items-center justify-center ${className || ''}`}>
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground/50" />
      </div>
    );
  }
  if (error || !url) {
    return (
      <div className={`flex items-center justify-center bg-muted ${className || ''}`}>
        <span className="text-xs text-muted-foreground">Failed to load</span>
      </div>
    );
  }
  return <img src={url} alt={alt} className={className} loading={loading} />;
}

export function AuthVideo({
  src,
  className,
  controls,
  autoPlay,
}: {
  src: string | null;
  className?: string;
  controls?: boolean;
  autoPlay?: boolean;
}) {
  const { url, loading, error } = useAuthMedia(src);

  if (loading) {
    return (
      <div className={`flex items-center justify-center bg-black ${className || ''}`}>
        <Loader2 className="h-8 w-8 animate-spin text-white/50" />
      </div>
    );
  }
  if (error || !url) {
    return (
      <div className={`flex items-center justify-center bg-muted ${className || ''}`}>
        <span className="text-sm text-muted-foreground">Failed to load video</span>
      </div>
    );
  }
  return <video src={url} className={className} controls={controls} autoPlay={autoPlay} />;
}

export function AuthAudio({
  src,
  className,
}: {
  src: string | null;
  className?: string;
}) {
  const { url, loading, error } = useAuthMedia(src);

  if (loading) {
    return (
      <div className={`flex items-center justify-center ${className || 'w-32 sm:w-40'}`}>
        <Loader2 className="h-4 w-4 animate-spin text-muted-foreground/50" />
      </div>
    );
  }
  if (error || !url) {
    return (
      <span className="text-xs text-muted-foreground">Failed to load</span>
    );
  }
  return <audio controls src={url} className={className || 'h-7 w-32 sm:w-40'} />;
}

export async function downloadAuthFile(url: string, filename: string): Promise<boolean> {
  const { fetchAuthBlob } = await import('@/services/api');
  const blob = await fetchAuthBlob(url);
  if (!blob) return false;
  const objUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(objUrl), 1000);
  return true;
}
