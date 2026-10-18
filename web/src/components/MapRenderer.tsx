import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { MapSurfaceProps } from "./MapSurface";

export default function MapRenderer({
  position,
  geometries,
  selectedId,
  draft,
  onPoint,
  onSelect,
  label,
  stale,
}: MapSurfaceProps) {
  const node = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const layers = useRef<L.LayerGroup | null>(null);
  const point = useRef<L.CircleMarker | null>(null);
  const fitted = useRef(false);
  const tileLayer = useRef<L.TileLayer | null>(null);
  const callbacks = useRef({ onPoint, onSelect });
  const [tileError, setTileError] = useState(false);
  const latitude = position?.latitude;
  const longitude = position?.longitude;
  useEffect(() => {
    callbacks.current = { onPoint, onSelect };
  }, [onPoint, onSelect]);
  useEffect(() => {
    if (!node.current) return;
    const reduced = window.matchMedia(
      "(prefers-reduced-motion: reduce)",
    ).matches;
    const view = L.map(node.current, {
      center: [0, 0],
      zoom: 2,
      scrollWheelZoom: false,
      zoomAnimation: !reduced,
      fadeAnimation: !reduced,
      attributionControl: true,
      zoomControl: false,
    });
    map.current = view;
    L.control.zoom({ position: "topright" }).addTo(view);
    layers.current = L.layerGroup().addTo(view);
    const tiles = L.tileLayer(
      "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
      {
        maxZoom: 19,
        referrerPolicy: "origin",
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      },
    ).addTo(view);
    tileLayer.current = tiles;
    const failed = new Set<HTMLElement>();
    tiles.on("tileerror", (event: L.TileErrorEvent) => {
      failed.add(event.tile);
      setTileError(true);
    });
    const clearTile = (event: L.TileEvent) => {
      failed.delete(event.tile);
      setTileError(failed.size > 0);
    };
    tiles.on("tileload", clearTile);
    tiles.on("tileunload", clearTile);
    view.on("click", (event: L.LeafletMouseEvent) =>
      callbacks.current.onPoint?.([
        Number(event.latlng.lng.toFixed(6)),
        Number(event.latlng.lat.toFixed(6)),
      ]),
    );
    const resize = new ResizeObserver(() => view.invalidateSize());
    resize.observe(node.current);
    return () => {
      resize.disconnect();
      view.remove();
      map.current = null;
      tileLayer.current = null;
      point.current = null;
      layers.current = null;
      fitted.current = false;
    };
  }, []);
  useEffect(() => {
    const view = map.current;
    if (!view) return;
    if (latitude === undefined || longitude === undefined) {
      point.current?.remove();
      point.current = null;
      return;
    }
    const to = L.latLng(latitude, longitude);
    if (!point.current)
      point.current = L.circleMarker(to, {
        radius: 9,
        color: "#ffffff",
        weight: 3,
        fillColor: "#087d8b",
        fillOpacity: 1,
      }).addTo(view);
    point.current.setStyle({ fillOpacity: stale ? 0.45 : 1 });
    if (!fitted.current) {
      view.setView(to, 14);
      fitted.current = true;
    }
    const marker = point.current;
    const from = marker.getLatLng();
    const start = performance.now();
    const duration = window.matchMedia("(prefers-reduced-motion: reduce)")
      .matches
      ? 0
      : 220;
    let frame = 0;
    const step = (now: number) => {
      const t = duration ? Math.min(1, (now - start) / duration) : 1;
      marker.setLatLng([
        from.lat + (to.lat - from.lat) * t,
        from.lng + (to.lng - from.lng) * t,
      ]);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [latitude, longitude, stale]);
  useEffect(() => {
    const group = layers.current;
    const view = map.current;
    if (!group || !view) return;
    group.clearLayers();
    const bounds = L.latLngBounds([]);
    for (const zone of geometries ?? []) {
      const shape = L.geoJSON(zone.geometry, {
        style: {
          color: zone.id === selectedId ? "#087346" : "#6c8178",
          weight: zone.id === selectedId ? 3 : 1.5,
          fillColor: "#168858",
          fillOpacity: zone.active ? 0.18 : 0.04,
          dashArray: zone.active ? undefined : "6 5",
        },
      });
      shape.on("click", (event: L.LeafletMouseEvent) => {
        L.DomEvent.stopPropagation(event);
        if (callbacks.current.onPoint)
          callbacks.current.onPoint([
            Number(event.latlng.lng.toFixed(6)),
            Number(event.latlng.lat.toFixed(6)),
          ]);
        else callbacks.current.onSelect?.(zone.id);
      });
      shape.addTo(group);
      bounds.extend(shape.getBounds());
    }
    if (draft?.length) {
      const points = draft.map(([lng, lat]) => L.latLng(lat, lng));
      (points.length > 2
        ? L.polygon(points, {
            color: "#a06d13",
            dashArray: "5 5",
            fillOpacity: 0.08,
          })
        : L.polyline(points, { color: "#a06d13" })
      ).addTo(group);
      points.forEach((p) => {
        L.circleMarker(p, { radius: 4, color: "#a06d13" }).addTo(group);
        bounds.extend(p);
      });
    }
    if (!fitted.current && bounds.isValid()) {
      view.fitBounds(bounds, { padding: [40, 40], maxZoom: 14 });
      fitted.current = true;
    }
  }, [geometries, selectedId, draft]);
  return (
    <div className="map-panel">
      <div className="map-caption">
        {label}
        {stale ? " · Last observed position" : ""}
      </div>
      <div ref={node} className="map-canvas" aria-label={label} />
      {tileError && (
        <div className="map-failure" role="status">
          Map tiles are unavailable. Authorized position and boundary overlays
          are still shown.
          <button
            type="button"
            className="button button-small"
            onClick={() => tileLayer.current?.redraw()}
          >
            Retry map tiles
          </button>
        </div>
      )}
    </div>
  );
}
