import { useRef, type FormEvent } from "react";
import { apiClient } from "../../auth/AuthContext";
import { decodeOrder } from "../../api/decoders";
import { useCommand } from "../../api/useResource";
import { ErrorState } from "../../components/States";
import { Notice } from "../../components/Ui";
export function CreateOrderForm({
  onCreated,
}: {
  onCreated: (id: string) => void;
}) {
  const command = useCommand();
  const key = useRef(crypto.randomUUID());
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const value = (name: string) => String(data.get(name) ?? "").trim();
    const body = {
      branchId: value("branch"),
      delivery: {
        line1: value("address"),
        city: value("city"),
        latitude: Number(value("latitude")),
        longitude: Number(value("longitude")),
      },
      currency: value("currency"),
      items: [
        {
          description: value("description"),
          quantity: Number(value("quantity")),
          unitAmount: Number(value("amount")),
        },
      ],
    };
    void command.execute(
      (signal) =>
        apiClient.post("/v1/orders", body, decodeOrder, {
          idempotencyKey: key.current,
          signal,
        }),
      (result) => onCreated(result.id),
    );
  };
  return (
    <form
      className="form-stack"
      onSubmit={submit}
      onChange={() => {
        key.current = crypto.randomUUID();
      }}
    >
      <Notice>
        Local test order. Submitted amounts are synthetic and do not represent
        trusted pricing.
      </Notice>
      <label className="field">
        Merchant branch ID
        <input
          name="branch"
          required
          pattern="[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
          spellCheck={false}
        />
      </label>
      <label className="field">
        Delivery address
        <input
          name="address"
          required
          maxLength={200}
          autoComplete="street-address"
        />
      </label>
      <div className="form-grid">
        <label className="field">
          City
          <input
            name="city"
            required
            maxLength={120}
            autoComplete="address-level2"
          />
        </label>
        <label className="field">
          Currency
          <input
            name="currency"
            defaultValue="EGP"
            required
            pattern="[A-Z]{3}"
            maxLength={3}
          />
        </label>
      </div>
      <div className="form-grid">
        <label className="field">
          Latitude
          <input
            name="latitude"
            type="number"
            min={-90}
            max={90}
            step="0.000001"
            required
          />
        </label>
        <label className="field">
          Longitude
          <input
            name="longitude"
            type="number"
            min={-180}
            max={180}
            step="0.000001"
            required
          />
        </label>
      </div>
      <label className="field">
        Item description
        <input name="description" required maxLength={200} />
      </label>
      <div className="form-grid">
        <label className="field">
          Quantity
          <input
            name="quantity"
            type="number"
            defaultValue="1"
            min={1}
            max={10000}
            required
          />
        </label>
        <label className="field">
          Unit amount
          <input
            name="amount"
            type="number"
            min={0}
            max={1000000}
            step="0.01"
            required
          />
        </label>
      </div>
      {command.problem && <ErrorState problem={command.problem} />}
      <button className="button button-primary" disabled={command.pending}>
        {command.pending ? "Creating order…" : "Create local order"}
      </button>
    </form>
  );
}
