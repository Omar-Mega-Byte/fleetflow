import type { MultiPolygon, Polygon } from "geojson";
import {
  array,
  record,
  id,
  text,
  integer,
  number,
  time,
} from "../api/decoders";
const optional = <T>(value: unknown, decode: (v: unknown) => T) =>
  value == null ? null : decode(value);
const bool = (value: unknown) => {
  if (typeof value !== "boolean") throw new Error("Invalid boolean");
  return value;
};
const page = <T>(value: unknown, decode: (v: unknown) => T, limit = 100) => ({
  content: array(record(value).content, limit).map(decode),
});
export const decodeAck = (value: unknown) => record(value);
export function decodeNotification(value: unknown) {
  const r = record(value);
  return {
    id: id(r.id),
    eventId: id(r.eventId),
    orderId: optional(r.orderId, id),
    assignmentId: optional(r.assignmentId, id),
    kind: text(r.kind),
    state: text(r.state),
    createdAt: time(r.createdAt),
  };
}
export const decodeNotifications = (value: unknown) =>
  page(value, decodeNotification, 20);
export function decodeAnalytics(value: unknown) {
  return page(
    value,
    (v) => {
      const r = record(v);
      return {
        day: time(r.day).slice(0, 10),
        status: text(r.status),
        total: integer(r.total),
      };
    },
    1000,
  );
}
export function decodeCandidate(value: unknown) {
  const r = record(value);
  return {
    driverId: id(r.driverId),
    reason: text(r.reason),
    distanceMeters: optional(r.distanceMeters, number),
    gpsAgeMillis: optional(r.gpsAgeMillis, integer),
    score: optional(r.score, number),
    rank: optional(r.rank, integer),
    straightLineSeconds: optional(r.straightLineSeconds, integer),
  };
}
export function decodeAttempt(value: unknown) {
  const r = record(value);
  return {
    id: id(r.id),
    sequence: integer(r.sequence),
    startedAt: time(r.startedAt),
    completedAt: optional(r.completedAt, time),
    outcome: text(r.outcome),
    configVersion: text(r.configVersion),
    zoneMode: text(r.zoneMode),
    zoneId: optional(r.zoneId, id),
    candidates: array(r.candidates, 1000).map(decodeCandidate),
  };
}
export const decodeAttempts = (value: unknown) =>
  page(value, decodeAttempt, 20);
export function decodeAssignment(value: unknown) {
  const r = record(value);
  return {
    offerId: id(r.offerId),
    driverId: id(r.driverId),
    attemptId: id(r.attemptId),
    rank: integer(r.rank),
    status: text(r.status),
    offeredAt: time(r.offeredAt),
    expiresAt: time(r.expiresAt),
    respondedAt: optional(r.respondedAt, time),
  };
}
export const decodeAssignments = (value: unknown) =>
  page(value, decodeAssignment, 20);
export function decodeOfferReceipt(value: unknown) {
  const r = record(value);
  return {
    offerId: id(r.offerId),
    status: text(r.status),
    expiresAt: time(r.expiresAt),
  };
}
export function decodeDriver(value: unknown) {
  const r = record(value);
  return {
    id: id(r.id),
    active: bool(r.active),
    suspended: bool(r.suspended),
    operationalStatus: text(r.operationalStatus),
    version: integer(r.version),
  };
}
export function decodeDriverOffer(value: unknown) {
  const r = record(value);
  return {
    offerId: id(r.offerId),
    orderId: id(r.orderId),
    status: text(r.status),
    offeredAt: time(r.offeredAt),
    expiresAt: time(r.expiresAt),
    pickupLine1: text(r.pickupLine1),
    pickupCity: text(r.pickupCity),
    deliveryLine1: text(r.deliveryLine1),
    deliveryCity: text(r.deliveryCity),
  };
}
export const decodeDriverOffers = (value: unknown) =>
  page(value, decodeDriverOffer, 20);
export function decodeDeliveries(value: unknown) {
  return page(
    value,
    (v) => {
      const r = record(v);
      return {
        deliveryId: id(r.deliveryId),
        orderId: id(r.orderId),
        status: text(r.status),
        sequence: integer(r.sequence),
        pickupLine1: text(r.pickupLine1),
        pickupCity: text(r.pickupCity),
        deliveryLine1: text(r.deliveryLine1),
        deliveryCity: text(r.deliveryCity),
      };
    },
    20,
  );
}
export function decodeMerchantOrders(value: unknown) {
  const r = record(value);
  return {
    ...page(value, (v) => {
      const o = record(v);
      return {
        id: id(o.id),
        status: text(o.status),
        currency: text(o.currency, 3),
        totalAmount: number(o.totalAmount),
        createdAt: time(o.createdAt),
      };
    }),
    page: integer(r.page),
    totalPages: integer(r.totalPages),
    totalElements: integer(r.totalElements),
  };
}
export function decodeGeometry(value: unknown): Polygon | MultiPolygon {
  const r = record(value);
  if (r.type !== "Polygon" && r.type !== "MultiPolygon")
    throw new Error("Expected polygon");
  let count = 0;
  const ring = (v: unknown) => {
    const points = array(v, 4096).map((p) => {
      const pair = array(p, 3);
      if (
        pair.length < 2 ||
        typeof pair[0] !== "number" ||
        typeof pair[1] !== "number" ||
        !Number.isFinite(pair[0]) ||
        !Number.isFinite(pair[1]) ||
        Math.abs(pair[0]) > 180 ||
        Math.abs(pair[1]) > 90 ||
        ++count > 20000
      )
        throw new Error("Invalid coordinate");
      return [pair[0], pair[1]];
    });
    if (
      points.length < 4 ||
      points[0]![0] !== points.at(-1)![0] ||
      points[0]![1] !== points.at(-1)![1] ||
      new Set(points.map((p) => p.join(","))).size < 3
    )
      throw new Error("Invalid closed ring");
    return points;
  };
  const polygon = (v: unknown) => {
    const rings = array(v, 100);
    if (!rings.length) throw new Error("Empty polygon");
    return rings.map(ring);
  };
  return r.type === "Polygon"
    ? { type: "Polygon", coordinates: polygon(r.coordinates) }
    : {
        type: "MultiPolygon",
        coordinates: array(r.coordinates, 100).map(polygon),
      };
}
export function decodeZone(value: unknown) {
  const r = record(value);
  return {
    id: id(r.id),
    branchId: id(r.branchId),
    name: text(r.name, 120),
    geometry: decodeGeometry(r.geometry),
    active: bool(r.active),
    version: integer(r.version),
    createdAt: time(r.createdAt),
    updatedAt: time(r.updatedAt),
  };
}
export const decodeZones = (value: unknown) =>
  array(value, 100).map(decodeZone);
export function decodePolicy(value: unknown) {
  const r = record(value);
  return { mode: text(r.mode), version: integer(r.version) };
}
export function decodeFailed(value: unknown) {
  return page(value, (v) => {
    const r = record(v);
    return {
      eventId: id(r.eventId),
      aggregateId: id(r.aggregateId),
      eventType: text(r.eventType),
      attempts: integer(r.attempts),
      replayCount: integer(r.replayCount),
      errorCode: text(r.errorCode),
      occurredAt: time(r.occurredAt),
    };
  });
}
export function decodeQuarantine(value: unknown) {
  return page(value, (v) => {
    const r = record(v);
    return {
      consumer: text(r.consumer),
      topic: text(r.topic),
      partition: integer(r.partition),
      offset: integer(r.offset),
      errorCode: text(r.errorCode),
      quarantinedAt: time(r.quarantinedAt),
    };
  });
}
export function decodeIncidents(value: unknown) {
  return page(
    value,
    (v) => {
      const r = record(v);
      return { id: id(r.id), reason: text(r.reason), status: text(r.status) };
    },
    20,
  );
}
export function decodeDemo(value: unknown) {
  const r = record(value);
  return {
    generation: integer(r.generation),
    result: text(r.result),
    orderIds: array(r.orderIds, 100).map(id),
  };
}
export type DemoState = ReturnType<typeof decodeDemo>;
export type Zone = ReturnType<typeof decodeZone>;
export type Notification = ReturnType<typeof decodeNotification>;
