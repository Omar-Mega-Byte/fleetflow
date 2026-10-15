import { describe, expect, it } from "vitest";
import {
  decodeCandidate,
  decodeGeometry,
  decodeNotifications,
  decodeZone,
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
});
