import { useState, useCallback, useEffect, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, GpsLocation } from '@/types';
import { CMD, extractList } from '@/types';
import { DevicePageHeader, ErrorAlert, SectionCard, StatusBadge, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { MapPin, Play, Square, ExternalLink, Navigation, ChevronLeft, ChevronRight, AlertCircle, X } from 'lucide-react';
import { clientsApi } from '@/services/api';
import { MapContainer, TileLayer, Marker, Popup, Circle, Polyline, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

const defaultIcon = L.icon({
  iconUrl: markerIcon,
  iconRetinaUrl: markerIcon2x,
  shadowUrl: markerShadow,
  iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41],
});
L.Marker.prototype.options.icon = defaultIcon;

function MapRecenter({ lat, lng, follow }: { lat: number; lng: number; follow: boolean }) {
  const map = useMap();
  useEffect(() => {
    if (follow) map.setView([lat, lng], 16, { animate: true });
  }, [lat, lng, map, follow]);
  return null;
}

function formatTime(time: string | number | undefined): string {
  if (!time) return '-';
  try {
    const d = typeof time === 'number' ? new Date(time) : new Date(time);
    if (isNaN(d.getTime())) return String(time);
    return d.toLocaleString();
  } catch { return String(time); }
}

export default function GpsPage() {
  const { client, clientId, online } = useOutletContext<DeviceOutletContext>();
  const [gpsInterval, setGpsInterval] = useState(client?.gpsInterval ?? 0);
  const [customInterval, setCustomInterval] = useState('30');
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [follow, setFollow] = useState(true);
  const mapRef = useRef<L.Map | null>(null);

  useEffect(() => {
    return () => {
      if (mapRef.current) {
        mapRef.current.remove();
        mapRef.current = null;
      }
    };
  }, []);

  const { data: rawData, loading, error, refresh, sendCommand, commandStatus, clearData } = useDeviceData<{
    locations: GpsLocation[];
    interval: number;
  }>({
    clientId,
    page: 'gps',
    extractData: (d) => ({
      locations: extractList<GpsLocation>(d.list).filter((loc) =>
        typeof loc.latitude === 'number' && typeof loc.longitude === 'number' &&
        loc.latitude !== 0 && loc.longitude !== 0
      ),
      interval: typeof d.interval === 'number' ? d.interval : 0,
    }),
    dataType: 'gps',
    defaultValue: { locations: [], interval: 0 },
  });

  const locations = rawData.locations;
  const serverInterval = rawData.interval;

  const dataActions = buildDataActions({ data: locations, exportPrefix: 'gps', onClear: clearData });

  useEffect(() => { setGpsInterval(serverInterval); }, [serverInterval]);

  const fetchGps = useCallback(async () => {
    setGpsError(null);
    try { await sendCommand(CMD.LOCATION); } catch { setGpsError('Failed to fetch location.'); }
  }, [sendCommand]);

  const startPolling = async () => {
    const val = parseInt(customInterval, 10);
    if (isNaN(val) || val < 1 || val > 3600) { setGpsError('Interval must be 1-3600 seconds.'); return; }
    setBusy(true); setGpsError(null);
    try {
      await clientsApi.setGps(clientId, val);
      setGpsInterval(val);
    } catch (err: any) {
      setGpsError(err?.response?.data?.error || 'Failed to start polling.');
    }
    setBusy(false);
  };

  const stopPolling = async () => {
    setBusy(true); setGpsError(null);
    try {
      await clientsApi.setGps(clientId, 0);
      setGpsInterval(0);
    } catch (err: any) {
      setGpsError(err?.response?.data?.error || 'Failed to stop polling.');
    }
    setBusy(false);
  };

  const latest = locations.length > 0 ? locations[locations.length - 1] : null;
  const historyPath = locations.map(l => [l.latitude, l.longitude] as [number, number]);

  const PAGE_SIZE = 20;
  const [historyPage, setHistoryPage] = useState(1);
  const totalHistoryPages = Math.max(1, Math.ceil(locations.length / PAGE_SIZE));
  const paginatedLocations = locations.slice((historyPage - 1) * PAGE_SIZE, historyPage * PAGE_SIZE);
  useEffect(() => { setHistoryPage(1); }, [locations.length]);

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="GPS Location"
        subtitle={`${locations.length} recorded locations`}
        actions={[
          { label: 'Fetch Location', icon: MapPin, onClick: fetchGps, disabled: !online },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}

      {gpsError && (
        <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
          <AlertCircle className="h-4 w-4 shrink-0" />
          {gpsError}
          <button onClick={() => setGpsError(null)} className="ml-auto"><X className="h-3.5 w-3.5" /></button>
        </div>
      )}

      <SectionCard>
        <div className="flex flex-col sm:flex-row sm:items-center gap-2">
          <div className="flex items-center gap-2">
            <Input type="number" placeholder="Interval" value={customInterval} onChange={(e) => setCustomInterval(e.target.value)} className="w-24 h-8 text-xs" min="1" max="3600" disabled={busy} />
            <span className="text-xs text-muted-foreground">sec</span>
            <Button onClick={startPolling} disabled={gpsInterval > 0 || !online || busy} size="sm" className="h-8">
              <Play className="h-3 w-3 mr-1" /> Start
            </Button>
            <Button onClick={stopPolling} variant="destructive" disabled={gpsInterval === 0 || !online || busy} size="sm" className="h-8">
              <Square className="h-3 w-3 mr-1" /> Stop
            </Button>
          </div>
          {gpsInterval > 0 && (
            <StatusBadge label={online ? `Polling every ${gpsInterval}s` : `Polling paused (offline)`} status={online ? 'success' : 'warning'} />
          )}
        </div>
      </SectionCard>

      {loading && !error ? (
        <LoadingSkeleton rows={4} />
      ) : (
        <>
          {}
          {latest && (
            <Card className="shadow-none overflow-hidden">
              <div className="h-[400px] w-full">
                <MapContainer
                  center={[latest.latitude, latest.longitude]}
                  zoom={16}
                  style={{ height: '100%', width: '100%' }}
                  ref={(m) => { mapRef.current = m; }}
                >
                  <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" attribution='&copy; OpenStreetMap' />
                  <MapRecenter lat={latest.latitude} lng={latest.longitude} follow={follow} />
                  {latest.accuracy && (
                    <Circle center={[latest.latitude, latest.longitude]} radius={latest.accuracy} pathOptions={{ color: 'blue', fillOpacity: 0.1 }} />
                  )}
                  <Marker position={[latest.latitude, latest.longitude]}>
                    <Popup>
                      <div className="text-xs">
                        <p className="font-bold">{latest.latitude.toFixed(6)}, {latest.longitude.toFixed(6)}</p>
                        {latest.accuracy && <p>Accuracy: {latest.accuracy}m</p>}
                        {latest.speed != null && <p>Speed: {latest.speed} m/s</p>}
                        <p>{formatTime(latest.time)}</p>
                      </div>
                    </Popup>
                  </Marker>
                  {historyPath.length > 1 && (
                    <Polyline positions={historyPath} pathOptions={{ color: 'red', weight: 2, opacity: 0.6 }} />
                  )}
                </MapContainer>
              </div>
              <div className="px-3 py-2 border-t flex items-center justify-between text-xs">
                <span className="text-muted-foreground">
                  {follow ? 'Following latest fix' : 'Free pan. Auto-follow off.'}
                </span>
                <Button
                  variant={follow ? 'default' : 'outline'}
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => setFollow(f => !f)}
                >
                  {follow ? 'Following' : 'Follow'}
                </Button>
              </div>
            </Card>
          )}

          {}
          {latest && (
            <Card className="shadow-none overflow-hidden">
              <div className="bg-gradient-to-br from-primary/5 via-primary/10 to-primary/5 p-5">
                <div className="flex items-start gap-4">
                  <div className="h-12 w-12 rounded-xl bg-primary/15 flex items-center justify-center shrink-0">
                    <Navigation className="h-6 w-6 text-primary" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium">Latest Position</p>
                    <p className="text-lg font-bold font-mono mt-1">{latest.latitude.toFixed(6)}, {latest.longitude.toFixed(6)}</p>
                    <div className="flex items-center gap-3 mt-1.5 text-xs text-muted-foreground flex-wrap">
                      <span>Accuracy: {latest.accuracy ? `${latest.accuracy}m` : 'N/A'}</span>
                      {latest.speed != null && <span>Speed: {latest.speed} m/s</span>}
                      <span>Provider: {latest.provider || 'N/A'}</span>
                      <span>{formatTime(latest.time)}</span>
                      {(latest as any).isMock && <Badge variant="outline" className="text-[10px] text-orange-600 border-orange-600/30">Mock</Badge>}
                    </div>
                    <a href={`https://www.google.com/maps?q=${latest.latitude},${latest.longitude}`} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 text-xs text-primary hover:underline mt-3">
                      <ExternalLink className="h-3 w-3" /> Open in Google Maps
                    </a>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {}
          <SectionCard title={`Location History (${locations.length})`} icon={MapPin}>
            {locations.length === 0 ? (
              <div className="py-6 text-center">
                <MapPin className="h-8 w-8 mx-auto mb-2 text-muted-foreground/40" />
                <p className="text-sm text-muted-foreground">No GPS data available</p>
                <p className="text-xs text-muted-foreground/50 mt-1">Click Fetch Location to get current position</p>
              </div>
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="text-xs">Latitude</TableHead>
                      <TableHead className="text-xs">Longitude</TableHead>
                      <TableHead className="text-xs">Accuracy</TableHead>
                      <TableHead className="text-xs hidden sm:table-cell">Speed</TableHead>
                      <TableHead className="text-xs hidden md:table-cell">Provider</TableHead>
                      <TableHead className="text-xs">Time</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {paginatedLocations.map((loc, i) => (
                      <TableRow key={`gps-${i}`}>
                        <TableCell className="font-mono text-xs">{loc.latitude.toFixed(6)}</TableCell>
                        <TableCell className="font-mono text-xs">{loc.longitude.toFixed(6)}</TableCell>
                        <TableCell className="text-xs">{loc.accuracy ? `${loc.accuracy}m` : '-'}</TableCell>
                        <TableCell className="text-xs hidden sm:table-cell">{loc.speed != null ? `${loc.speed} m/s` : '-'}</TableCell>
                        <TableCell className="hidden md:table-cell"><Badge variant="outline" className="text-[10px]">{loc.provider || '-'}</Badge></TableCell>
                        <TableCell className="text-xs text-muted-foreground whitespace-nowrap">{formatTime(loc.time)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                {totalHistoryPages > 1 && (
                  <div className="flex items-center justify-between mt-3 pt-3 border-t">
                    <span className="text-xs text-muted-foreground">Page {historyPage} of {totalHistoryPages} ({locations.length} total)</span>
                    <div className="flex items-center gap-1">
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => setHistoryPage(p => Math.max(1, p - 1))} disabled={historyPage <= 1}>
                        <ChevronLeft className="h-3.5 w-3.5" />
                      </Button>
                      <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => setHistoryPage(p => Math.min(totalHistoryPages, p + 1))} disabled={historyPage >= totalHistoryPages}>
                        <ChevronRight className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
