export type PersonaRole = 'CUSTOMER' | 'MERCHANT' | 'DRIVER' | 'DISPATCHER' | 'ADMIN' | 'SUPPORT';

export interface SessionView { roles: PersonaRole[] }
export interface OrderSummary {
  id: string;
  branchId: string;
  status: string;
  branchName: string;
  currency: string;
  totalAmount: number;
  createdAt: string;
}
export interface OrderPage {
  content: OrderSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface OrderView extends OrderSummary {
  customerId: string;
  version: number;
  customerName: string;
  merchantName: string;
  pickup: { line1: string; city: string };
  delivery: { line1: string; city: string };
  items: Array<{ lineNumber: number; description: string; quantity: number; unitAmount: number; lineAmount: number }>;
}
export interface TrackingSnapshot {
  orderId: string;
  orderStatus: string;
  orderVersion: number;
  deliveryStatus: string | null;
  observedAt: string;
  position: { latitude: number; longitude: number; reportedAt: string } | null;
  terminal: boolean;
}
