# Adapted ONU Models

English | [简体中文](./ADAPTED_MODELS.md)

Only two columns are tracked: **operator** + **vendor model**. Add one line per successfully adapted device — no version numbers or capability items, since those change over time.

| Operator | Vendor model |
| --- | --- |
| CMCC (China Mobile) | CIOT P22g-CIac |

---

## Notes

- Adaptation profiles are stored in the portable SQLite template library `configuration/tr069_tpl.pack` (property `acs.tr069tpl.db-path`),
  which ships with the repository: copy it to the deployment host and the models are recognized as adapted.
- Only devices with `device.status = ADAPTED` count as adapted; `PENDING` is just an auto-registered placeholder awaiting adaptation and does not take part in delivery.
- This table and the database are two views of the same thing: one row inserted successfully into the library means one row added here.

## Adding a new device

Device list → select the device → Model adaptation (declare operator / port count / port speed / SIP count / WiFi specs)
→ trial collection → save once it passes. Saving writes into the template library; then add one row to this table.
