# Play release external-input checklist

These inputs belong to the publisher and are intentionally absent from source. Their absence disables publication, not application correctness, and never activates a mock endpoint or fake credential.

| Input | Required validation before upload | Repository behavior while absent |
|---|---|---|
| Final `applicationId` and Play listing identity | Reverse-DNS ID is approved and passed as `-PledgerApplicationId`; it matches Play Console, OAuth and policy records | Development identity builds an explicitly non-publishable unsigned candidate |
| Upload key and Play App Signing enrollment | All four `ledgerSigning*` properties arrive from a secret store; signed bundle verification and Play certificate fingerprints pass | No release signing config is created; partial configuration fails |
| Google Cloud Android OAuth authorization for Drive `drive.file` | Final package plus Play signing SHA-256 fingerprints are registered; Drive API and consent screen are production-approved | Authorization fails closed; no client secret or fake token is embedded |
| Telemetry and crash receiver | Publisher approves HTTPS URL, certificate policy, schema, 90/180-day retention and deletion operations | Consent-gated events remain local; no success is reported |
| Policy/support/source URLs | HTTPS privacy-policy URL serves all three languages; support contact and source URL are final and match the store listing | Local policies and About remain usable; store upload is blocked |
| Store metadata and assets | Name, descriptions, category, data-safety answers, content rating, screenshots generated from the shipped build, and regional declarations are approved | No synthetic listing or visual-draft-derived asset is committed |

The four excluded visual drafts are not release sources and must never be used to generate or compare store screenshots.
