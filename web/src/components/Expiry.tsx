import { useEffect, useState } from "react";
export function useNow() {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
export function Expiry({ at }: { at: string }) {
  const now = useNow();
  const left = Math.max(0, Math.ceil((Date.parse(at) - now) / 1000));
  return (
    <span className="mono">
      {left
        ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")} remaining`
        : "Expired"}
    </span>
  );
}
