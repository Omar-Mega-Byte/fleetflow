import { describe, expect, it } from 'vitest';
import { decodeOrderPage, decodeSession, decodeTrackingSnapshot } from './decoders';

const id = '00000000-0000-0000-0000-000000000001';
const order = { id, branchId: id, branchName: 'Synthetic branch', currency: 'EGP', totalAmount: 245,
  status: 'CREATED', createdAt: '2026-10-14T12:00:00Z' };
const page = { content: [order], page: 0, size: 20, totalElements: 1, totalPages: 1 };
describe('runtime API contracts', () => {
  it('validates nested fields rather than trusting the presence of object keys', () => {
    expect(decodeOrderPage(page).content[0]?.totalAmount).toBe(245);
    for (const invalid of [{ totalAmount: '245' }, { createdAt: 'invalid' }, { status: 'UNKNOWN' },
      { currency: 'NOT_A_CURRENCY' }, { id: null }, { branchName: {} }]) {
      expect(() => decodeOrderPage({ ...page, content: [{ ...order, ...invalid }] })).toThrow();
    }
    expect(() => decodeOrderPage({ ...page, content: Array(101).fill(order) })).toThrow();
  });
  it('rejects malformed session roles and invalid tracking values', () => {
    expect(decodeSession({ roles: ['CUSTOMER', 'DRIVER'] }).roles).toEqual(['CUSTOMER', 'DRIVER']);
    expect(() => decodeSession({ roles: ['UNKNOWN'] })).toThrow();
    const snapshot = { orderId: id, orderStatus: 'CREATED', orderVersion: 0,
      observedAt: '2026-10-14T12:00:00Z', terminal: false, position: null };
    expect(decodeTrackingSnapshot(snapshot).deliveryStatus).toBeNull();
    expect(() => decodeTrackingSnapshot({ ...snapshot, terminal: 'false' })).toThrow();
    expect(() => decodeTrackingSnapshot({ ...snapshot, position: { latitude: 91, longitude: 30, reportedAt: snapshot.observedAt } })).toThrow();
  });
});
