import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { PersonaRole } from './contracts';

const auth = vi.hoisted(() => ({ status: 'unauthenticated' as 'unauthenticated' | 'authenticated' | 'loading', roles: [] as PersonaRole[] }));
vi.mock('./auth/AuthContext', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ ...auth, signIn: vi.fn(), signOut: vi.fn(), completeSignIn: vi.fn(), reauthenticate: vi.fn() }),
  apiClient: { get: vi.fn() },
}));

import { ProtectedRoute } from './App';

describe('protected navigation', () => {
  beforeEach(() => { auth.status = 'unauthenticated'; auth.roles = []; });

  it('redirects unauthenticated visitors to login with a return path', () => {
    render(<MemoryRouter initialEntries={['/orders/owned']}><Routes>
      <Route path="/orders/owned" element={<ProtectedRoute><p>Private order</p></ProtectedRoute>} />
      <Route path="/login" element={<p>Sign in</p>} />
    </Routes></MemoryRouter>);
    expect(screen.getByText('Sign in')).toBeVisible();
    expect(screen.queryByText('Private order')).not.toBeInTheDocument();
  });

  it('forbids a signed-in persona without the customer role', () => {
    auth.status = 'authenticated';
    auth.roles = ['DRIVER'];
    render(<MemoryRouter initialEntries={['/orders/owned']}><Routes>
      <Route path="/orders/owned" element={<ProtectedRoute role="CUSTOMER"><p>Private order</p></ProtectedRoute>} />
      <Route path="/forbidden" element={<p>Access unavailable</p>} />
    </Routes></MemoryRouter>);
    expect(screen.getByText('Access unavailable')).toBeVisible();
  });
});
