import { describe, expect, it } from "vitest";
import {
  decodeCandidate,
  decodeGeometry,
  decodeNotifications,
  decodeReplay,
  decodeZone,
  decodeDemoDriverRoute,
  decodeServiceability,
} from "./contracts";
const id = "10000000-0000-4000-8000-000000000001";
describe("operational response boundaries", () => {
  it("keeps unavailable candidate evidence nullable instead of inventing a score", () => {
    const candidate = decodeCandidate({
      driverId: id,
      reason: "MISSING_LOCATION",
      distanceMeters: null,
      gpsAgeMillis: null,
      score: null,
      rank: null,
      straightLineSeconds: null,
    });
    expect(candidate.score).toBeNull();
    expect(candidate.rank).toBeNull();
    expect(() => decodeCandidate({ ...candidate, score: "0.9" })).toThrow();
  });
  it("rejects invalid geographic coordinates and unsupported geometries", () => {
    expect(() =>
      decodeGeometry({ type: "MultiPolygon", coordinates: [] }),
    ).toThrow();
    expect(() =>
      decodeGeometry({
        type: "Polygon",
        coordinates: [
          [
            [181, 30],
            [31, 30],
            [31, 31],
            [181, 30],
          ],
        ],
      }),
    ).toThrow();
    expect(() =>
      decodeGeometry({ type: "Point", coordinates: [31, 30] }),
    ).toThrow();
    const shape = decodeGeometry({
      type: "MultiPolygon",
      coordinates: [
        [
          [
            [31, 30],
            [32, 30],
            [32, 31],
            [31, 30],
          ],
        ],
      ],
    });
    expect(shape.type).toBe("MultiPolygon");
  });
  it("accepts only bounded fixture positions and explicit branch coverage", () => {
    const positions = Array.from({ length: 5 }, () => ({
      latitude: 30,
      longitude: 31,
    }));
    expect(
      decodeDemoDriverRoute({ generation: 2, points: positions }).points,
    ).toHaveLength(5);
    expect(() =>
      decodeDemoDriverRoute({
        generation: 2,
        points: [...positions, positions[0]],
      }),
    ).toThrow();
    expect(() =>
      decodeDemoDriverRoute({
        generation: 2,
        points: positions.map(() => ({ latitude: 91, longitude: 31 })),
      }),
    ).toThrow();
    expect(
      decodeServiceability({
        supported: false,
        mode: "ENFORCED",
        policyVersion: 3,
        zoneId: null,
      }).supported,
    ).toBe(false);
    expect(() =>
      decodeServiceability({
        supported: "true",
        mode: "ENFORCED",
        policyVersion: 3,
        zoneId: null,
      }),
    ).toThrow();
  });
  it("rejects malformed zone versions and unbounded recipient activity", () => {
    expect(() =>
      decodeZone({
        id,
        branchId: id,
        name: "Branch zone",
        geometry: { type: "Polygon", coordinates: [] },
        active: true,
        version: -1,
        createdAt: "2026-10-14",
        updatedAt: "2026-10-14",
      }),
    ).toThrow();
    expect(() =>
      decodeNotifications({ content: Array(21).fill({}) }),
    ).toThrow();
  });
  it("accepts only explicit operator replay outcomes", () => {
    expect(decodeReplay({ eventId: id, outcome: "REPLAYED" }).outcome).toBe(
      "REPLAYED",
    );
    expect(
      decodeReplay({ eventId: id, outcome: "REPLAY_LIMIT_REACHED" }).outcome,
    ).toBe("REPLAY_LIMIT_REACHED");
    expect(() => decodeReplay({ eventId: id, outcome: "QUEUED" })).toThrow();
  });
});
