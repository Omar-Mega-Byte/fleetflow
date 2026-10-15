# First exploration critique and selection

2026-10-14. Reviewer inspected all three rendered desktop directions and recorded mobile overflow. Sources preserved under `exploration-v1/`; screenshots and DOM reports under `evidence/exploration/`.

## Selection

Use **A's operational SaaS composition** as the foundation. Borrow only C's dark evergreen navigation treatment and emphasis on exception/recovery context. Use B's map/detail relationship specifically in Tracking and Zones. This is one product with a light working canvas, not alternating unrelated visual themes.

## Findings requiring refinement

- A: desktop table clips at its right edge; mobile document overflows at 390px. Fix container sizing and recompose rows, not simply hide overflow. It still leaves excessive unused space and uses too many implementation caveats in primary copy.
- B: schematic geographic surface is useful as a layout study but too coarse for a production map. Do not implement that geography as a real map. Final map must use real basemap/authorized coordinates or an explicit unavailable state. Selected sheet screenshot was taken during entry; wait for the transition before judging its final contrast.
- C: the dark shell/light content split is coherent; the tiny abbreviated rail labels and small system text are hard to scan. Main viewport remains sparse; exception cards need tighter relationship to the active object. Keep system details in context drawers or explicit technical views.
- Across directions: prototype tables mix role examples in ways a single-role customer cannot read. Final demonstration needs a role selector and consistent data per role. Do not present “customer context” while showing dispatcher/merchant-only object data.
- Public `OrderView` returns `PublicAddress(line1,city)`. The first commissioning brief incorrectly claimed pickup/destination coordinates were exposed and discarded by the frontend. Remove map pickup/destination markers and connectors that depend on those fields. Customer map can show the optional authorized driver position only. Merchant zones use actual authorized GeoJSON.
- Remove API/PKCE/query-parameter explanations from ordinary task flows. Preserve truth through useful labels such as “Search this page,” “Last observed,” “Waiting for location,” and contextual state feedback. Put detailed source/contract evidence in a help or technical details drawer.
- Need final token values, full component specimens, accessible dialog focus behavior, readable mobile navigation, and stronger operational identity. The initial directions are exploration, not accepted implementation targets.

## Second-pass requirements

Refine only the selected direction. Deep evergreen sidebar with readable icons/labels; white/cool neutral workspace; compact top context bar; 28px page titles; 14px body/controls; >=12px meaningful metadata; crisp thin borders; 6–10px corner system. Emerald action, cyan fresh position, amber waiting, red intervention. One consistent type family plus monospace metadata. No decorative glow/gradients.

Create a final interactive prototype covering Command Center, Orders, Detail, Tracking, Dispatch, Zones, Activity and Operations; role-select appropriate surfaces. Include real-contract empty/loading/error/partial states, compact lifecycle exceptions, matching evidence, offers/expiry, zone editor validation/conflict, notifications and bounded recovery inspection. Keep preview data clearly synthetic. Include desktop and mobile checks and token/component/motion specification. Do not revise the three source explorations.
