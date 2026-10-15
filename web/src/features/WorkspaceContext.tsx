import { createContext, useContext } from "react";
interface WorkspaceScope {
  branchId: string;
  orderId: string;
  setBranchId: (value: string) => void;
  setOrderId: (value: string) => void;
}
export const WorkspaceContext = createContext<WorkspaceScope>({
  branchId: "",
  orderId: "",
  setBranchId: () => {},
  setOrderId: () => {},
});
export const useWorkspace = () => useContext(WorkspaceContext);
