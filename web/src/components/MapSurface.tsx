import { lazy, Suspense } from "react";
import type { Polygon, MultiPolygon } from "geojson";
import { LoadingState } from "./States";
export interface MapSurfaceProps {
  position?: { latitude: number; longitude: number } | null;
  geometries?: Array<{
    id: string;
    geometry: Polygon | MultiPolygon;
    active: boolean;
  }>;
  selectedId?: string;
  draft?: Array<[number, number]>;
  onPoint?: (point: [number, number]) => void;
  onSelect?: (id: string) => void;
  label: string;
  stale?: boolean;
}
const MapRenderer = lazy(() => import("./MapRenderer"));
export function MapSurface(props: MapSurfaceProps) {
  return (
    <Suspense fallback={<LoadingState label="Preparing geographic view" />}>
      <MapRenderer {...props} />
    </Suspense>
  );
}
