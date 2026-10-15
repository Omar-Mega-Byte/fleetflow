import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { EmptyState, ErrorState, LoadingState, TrackingState } from './States';
import { ApiProblem } from '../api/client';

describe('shared user states', () => {
  it('announces loading, empty, errors, and stale tracking accessibly', () => {
    const { rerender } = render(<LoadingState label="Loading orders" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading orders');
    rerender(<EmptyState title="No orders yet"><p>Check again later.</p></EmptyState>);
    expect(screen.getByRole('heading', { name: 'No orders yet' })).toBeVisible();
    rerender(<ErrorState problem={new ApiProblem('rate-limited')} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Wait a moment');
    rerender(<TrackingState state="stale" />);
    expect(screen.getByRole('status')).toHaveTextContent('Last update may be out of date');
  });
});
