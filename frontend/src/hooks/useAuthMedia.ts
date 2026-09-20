import { useState, useEffect, useRef } from 'react';
import { fetchAuthBlob } from '@/services/api';

export function useAuthMedia(url: string | null): {
  url: string | null;
  loading: boolean;
  error: boolean;
} {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const currentUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (!url) {
      setObjectUrl(null);
      setLoading(false);
      setError(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(false);

    fetchAuthBlob(url).then((blob) => {
      if (cancelled) return;
      if (!blob) {
        setObjectUrl(null);
        setLoading(false);
        setError(true);
        return;
      }

      if (currentUrlRef.current) {
        URL.revokeObjectURL(currentUrlRef.current);
      }
      const objUrl = URL.createObjectURL(blob);
      currentUrlRef.current = objUrl;
      setObjectUrl(objUrl);
      setLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, [url]);

  useEffect(() => {
    return () => {
      if (currentUrlRef.current) {
        URL.revokeObjectURL(currentUrlRef.current);
        currentUrlRef.current = null;
      }
    };
  }, []);

  return { url: objectUrl, loading, error };
}
