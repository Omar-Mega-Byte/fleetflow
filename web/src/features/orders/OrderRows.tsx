import { Link } from "react-router-dom";
import type { OrderSummary } from "../../contracts";
import {
  Icon,
  StatusBadge,
  shortId,
  dateTime,
  money,
} from "../../components/Ui";
export function OrderRows({ orders }: { orders: OrderSummary[] }) {
  return (
    <div className="order-list">
      {orders.map((order) => (
        <Link
          className="order-card"
          to={`/customer/orders/${order.id}`}
          key={order.id}
        >
          <span className="order-icon">
            <Icon name="orders" />
          </span>
          <span className="order-card-main">
            <span className="order-merchant">{order.branchName}</span>
            <span className="order-meta">
              <span className="mono">{shortId(order.id)}</span> ·{" "}
              {dateTime(order.createdAt)}
            </span>
          </span>
          <span className="order-card-end">
            <StatusBadge status={order.status} />
            <span className="order-total">
              {money(order.totalAmount, order.currency)}
            </span>
          </span>
          <span className="order-chevron">
            <Icon name="arrow" size={16} />
          </span>
        </Link>
      ))}
    </div>
  );
}
