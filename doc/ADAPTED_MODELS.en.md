# Adapted ONT Models

[English](./ADAPTED_MODELS.en.md) | [简体中文](./ADAPTED_MODELS.md)

The adapted table keeps two columns only: **ISP** + **vendor model**. Add one row per successfully
adapted model — no version numbers or capability details, since those change over time.
Models still in progress (capability tree collected, or under investigation) go into the
"In progress" table.

## Adapted

| ISP | Vendor model |
| --- | --- |
| CMCC (China Mobile) | CIOT P22g-CIac |
| CMCC (China Mobile) | CIOT ZNM802 |
| CMCC (China Mobile) | MSG2200C-UP-4E4V |
| CT (China Telecom) | B866-S1-4E1P3W1-Ethernet |

> `B866-S1-4E1P3W1` hardware is **XGPON** uplink. After this unit was switched to **Ethernet uplink**
> it reports ProductClass `B866-S1-4E1P3W1-Ethernet`, and the model XML is archived under that
> ProductClass (`b866-s1-4e1p3w1-ethernet_v5r022c01s300.xml`, 565 objects / 3018 parameters).
> Changing the uplink type changes `DeviceInfo.ProductClass`, which is effectively a different model —
> it has to go through the adaptation flow again.

## In progress

| ISP | Vendor model | Status |
| --- | --- | --- |
| CT (China Telecom) | HS8145C5-GPON | Capability tree archived (360 objects / 1947 parameters), awaiting declaration and save |
| CT (China Telecom) | MSG2100E-UPON-8V-AC | GPN firmware unusable (always 9002); 26 objects / 12 parameters retrieved via known paths, awaiting declaration and save; ISP pending ledger confirmation (Inform reported `X_CT-COM_UserInfo`) |

---

## Notes

- Adaptation records live in the portable SQLite template library `configuration/tr069_tpl.pack`
  (property `acs.tr069tpl.db-path`). It ships with the repository — copy it to a deployment host
  and those models are recognized as adapted.
- A model counts as adapted only when `device.status = ADAPTED` in that library. `PENDING` is just an
  auto-registered placeholder and is never used for delivery, so "in progress" models are still
  `PENDING` there.
- Collected capability trees are stored at `configuration/models/<isp>/<model>_<version>.xml`.
  Archiving the tree only means the tree was retrieved: you still have to declare ISP / LAN ports /
  WiFi / SIP on the model adaptation page and save it. After saving, a `<model>_<version>.yml`
  appears next to the XML with `adapted: true`.
- This table and the library are two views of the same thing: one row here per successful save,
  and a model moves from "In progress" to "Adapted" once it is saved.

## Adapting a new model

Device list → select the device → Model adaptation (declare ISP / LAN port count / port speed /
SIP count / WiFi spec) → test collection → save once it passes. Saving writes to the template
library; then add a row here.

## How WiFi bands are detected

When the WiFi spec is declared, "test collection" has to work out the device's actual radio
capabilities. Vendors report them in very different ways, so the following rules are combined
(all in `worklist/service/ModelAdaptationService`):

| Rule | Detail | Example |
| --- | --- | --- |
| Band parameter | `OperatingFrequencyBand` / `SupportedFrequencyBands` reporting `2.4GHz`, `5GHz` | `X_CMCC_FrequencyBand` on the China Mobile P22g |
| Channel list | `PossibleChannels` / `SupportedChannels` / `ChannelsInUse`: channel ≤ 14 means 2.4G, ≥ 36 means 5.8G | `1-13` → 2.4G, `36 40 44 … 161` → 5.8G |
| 802.11 standard | `Standard` containing `ac`/`ax` means 5.8G, `b`/`g` means 2.4G; a bare `n` is inconclusive (both bands) | `bgn` → 2.4G, `anac` → 5.8G |
| SSID wording | Only counts when the SSID itself carries `2.4` / `5G` | `CMCC-xxxx-5G` → 5.8G |

Two fallback rules:

1. **Only 5G labelled, 2.4G unlabelled → treat as dual-band.** This is the industry convention
   (`CMCC-xxxx-5G` paired with a bare `CMCC-xxxx`). When 5.8G is detected but some radios still
   have no band, the unlabelled one is counted as 2.4G. The reverse does not hold: a single radio
   labelled 5G is a pure 5G device, and no 2.4G is added.
2. **No band detected at all → infer from the radio count.** One radio means 2.4G, two radios mean
   dual-band. Old firmware reports neither the band parameter nor any band wording in the SSID
   (`ChinaNet-xxxx`); without this fallback a 2.4G declaration could never pass.
