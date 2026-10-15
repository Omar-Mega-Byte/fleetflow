import type { OrderPage, OrderSummary, OrderView, PersonaRole, SessionView, TrackingSnapshot } from '../contracts';

export type Decoder<T> = (value: unknown) => T;
const statuses = new Set(['CREATED', 'CONFIRMED', 'PREPARING', 'READY_FOR_PICKUP', 'DISPATCHING',
  'DISPATCH_FAILED', 'DRIVER_ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'DELIVERY_FAILED', 'CANCELLED']);
const roles = new Set(['CUSTOMER', 'MERCHANT', 'DRIVER', 'DISPATCHER', 'ADMIN', 'SUPPORT']);
function invalid(): never { throw new Error('Invalid API response'); }
export function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
}
export function text(value: unknown, max = 500): string {
  return typeof value === 'string' && value.length > 0 && value.length <= max ? value : invalid();
}
export function id(value: unknown): string {
  const result = text(value, 36);
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(result) ? result : invalid();
}
export function number(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : invalid();
}
export function integer(value: unknown): number { const result = number(value); return Number.isSafeInteger(result) ? result : invalid(); }
export function time(value: unknown): string { const result = text(value, 64); return Number.isFinite(Date.parse(result)) ? result : invalid(); }
function status(value: unknown): string { const result = text(value, 40); return statuses.has(result) ? result : invalid(); }
export function array(value: unknown, max: number): unknown[] { return Array.isArray(value) && value.length <= max ? value : invalid(); }

export function decodeSession(value: unknown): SessionView {
  return { roles: array(record(value).roles, roles.size).map(role => {
    const result = text(role, 32);
    return roles.has(result) ? result as PersonaRole : invalid();
  }) };
}
function summary(value: unknown): OrderSummary {
  const row = record(value);
  const currency = text(row.currency, 3);
  if (!/^[A-Z]{3}$/.test(currency)) invalid();
  try { new Intl.NumberFormat('en', { style: 'currency', currency }); } catch { invalid(); }
  return { id: id(row.id), branchId: id(row.branchId), status: status(row.status), branchName: text(row.branchName),
    currency, totalAmount: number(row.totalAmount), createdAt: time(row.createdAt) };
}
export function decodeOrderPage(value: unknown): OrderPage {
  const row = record(value);
  return { content: array(row.content, 100).map(summary), page: integer(row.page), size: integer(row.size),
    totalElements: integer(row.totalElements), totalPages: integer(row.totalPages) };
}
export function decodeOrder(value: unknown): OrderView {
  const row = record(value);
  const address = (input: unknown) => { const item = record(input); return { line1: text(item.line1), city: text(item.city) }; };
  return { ...summary(row), customerId: id(row.customerId), version: integer(row.version),
    customerName: text(row.customerName), merchantName: text(row.merchantName), pickup: address(row.pickup), delivery: address(row.delivery),
    items: array(row.items, 100).map(input => { const item = record(input); return {
      lineNumber: integer(item.lineNumber), description: text(item.description), quantity: integer(item.quantity),
      unitAmount: number(item.unitAmount), lineAmount: number(item.lineAmount),
    }; }) };
}
export function decodeTrackingSnapshot(value: unknown): TrackingSnapshot {
  const row = record(value);
  let position: TrackingSnapshot['position'] = null;
  if (row.position != null) {
    const fix = record(row.position);
    if (typeof fix.latitude !== 'number' || !Number.isFinite(fix.latitude) || Math.abs(fix.latitude) > 90
      || typeof fix.longitude !== 'number' || !Number.isFinite(fix.longitude) || Math.abs(fix.longitude) > 180) invalid();
    position = { latitude: fix.latitude, longitude: fix.longitude, reportedAt: time(fix.reportedAt) };
  }
  if (typeof row.terminal !== 'boolean') invalid();
  return { orderId: id(row.orderId), orderStatus: status(row.orderStatus), orderVersion: integer(row.orderVersion),
    deliveryStatus: row.deliveryStatus == null ? null : text(row.deliveryStatus, 32),
    observedAt: time(row.observedAt), position, terminal: row.terminal };
}
