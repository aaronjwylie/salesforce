# Salesforce org setup

Everything the service needs from a live org, and how to get it. Takes about 20 minutes.

## 1. Get a Developer Edition org

https://developer.salesforce.com/signup — free, permanent, and it has the Pub/Sub API
enabled. A scratch org works too if you already have a Dev Hub, but DE is less hassle
for a long-lived demo.

Use an email you can still read in six months; the org is tied to it.

## 2. Enable Orders

Setup → Order Settings → **Enable Orders**. Also tick *Enable Negative Quantity* only
if you want to test credits; not required.

Without this the `Order` object is not exposed and the deploy in step 4 fails.

## 3. Create the OAuth app

Newer orgs give you **External Client Apps**; older ones give **Connected Apps**. Both
work — the settings live in different places.

### External Client App (current UI)

Setup → **External Client App Manager** → **New External Client App**.

| Field | Value |
| --- | --- |
| External Client App Name | `order_sync` |
| Contact Email | yours |
| Distribution State | Local |
| Enable OAuth | ✅ |
| Callback URL | `http://localhost:1717/OauthRedirect` |
| OAuth Scopes | `Manage user data via APIs (api)`, `Perform requests at any time (refresh_token, offline_access)` |

Then, in **OAuth Settings → Flow Enablement**, tick **Enable Client Credentials Flow**
and save.

Now open the app again and go to the **Policies** tab → **Edit** → **OAuth Policies** →
**Client Credentials Flow** → set **Run As** to your admin user.

> The Run As picker does not exist until the client credentials flow is enabled and
> saved in Settings. If Policies is read-only, the app is still a draft — publish it
> first.

### Connected App (classic UI)

Setup → App Manager → **New Connected App**, same OAuth fields as above, then
**Manage → Edit Policies**:

- Permitted Users: *Admin approved users are pre-authorized*
- **Run As**: your admin user
- **Manage Profiles** → add *System Administrator*

### Either way

OAuth changes take **2–10 minutes** to propagate. A token call that fails right after
saving is not necessarily misconfigured — wait and retry before debugging anything else.

A bare `invalid_grant` with no further detail is, nine times out of ten, a missing
Run As user.

## 4. Deploy the package

```bash
cd salesforce
sf org login web --alias ordersync-dev
sf project deploy start --target-org ordersync-dev
sf org assign permset --name Order_Sync_Integration --target-org ordersync-dev
sf apex run test --target-org ordersync-dev --code-coverage --result-format human
```

This creates `Order_Change__e`, the Apex publisher and trigger, the LWC status panel,
and the external id fields on Account and Order.

> **Do not skip the permission set.** Custom fields deploy with no field-level security
> granted to anyone, administrators included. Until it is assigned, any `USER_MODE`
> query or REST call reports the new fields as *"No such column 'ERP_Order_Id__c' on
> entity 'Order'"* — which reads exactly like a failed deploy rather than a permissions
> problem. The Apex tests fail the same way, and the deploy itself still reports success,
> because Apex compiles in system mode where FLS does not apply.

If `sf org login web` hangs and the browser shows `ERR_CONNECTION_REFUSED` on
`localhost:1717`, the CLI's callback listener was not running when Salesforce redirected
back — usually because the command exited or its terminal was closed. Re-run it and
leave the terminal open. Note the listener binds `[::1]` (IPv6) only, so a browser that
resolves `localhost` to `127.0.0.1` will also be refused.

## 5. Collect the three values

From the Connected App: **View** → Consumer Key and Consumer Secret.

Your org id:

```bash
sf org display --target-org ordersync-dev --json | jq -r '.result.id'
```

Put them in a `.env` at the repo root — it is gitignored, and these are live
credentials to a real org:

```bash
SF_LOGIN_URL=https://login.salesforce.com
SF_CLIENT_ID=3MVG9...
SF_CLIENT_SECRET=1A2B3C...
SF_ORG_ID=00Dxx0000000000EAA
```

The Pub/Sub endpoint (`api.pubsub.salesforce.com:7443`) is the same for every org and is
already in `application.yml`.

## 6. Make some test data

The demo needs an Account with an external id, since that is the ERP's join key:

```bash
sf data create record --target-org ordersync-dev --sobject Account \
  --values "Name='Northwind Traders' External_Id__c='ACCT-42'"
```

Orders can then be created in the UI: **Accounts → Northwind Traders → Orders → New**,
with a Start Date and Status *Draft*. Activating it is what fires the platform event.

> Salesforce will not let you activate an order that has no products, so add an order
> product first. That needs a Price Book entry — the org's standard Price Book with any
> product added to it is enough.

## Verifying auth before the service exists

```bash
curl -s -X POST "$SF_LOGIN_URL/services/oauth2/token" \
  -d grant_type=client_credentials \
  -d client_id="$SF_CLIENT_ID" \
  -d client_secret="$SF_CLIENT_SECRET" | jq
```

A JSON body with `access_token` and `instance_url` means the org side is done. Anything
else is a step 3 problem, not a code problem.
