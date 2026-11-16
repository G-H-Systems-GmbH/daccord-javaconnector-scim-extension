# Examples – daccord JavaConnector Extension SCIM 2.0

Request files for testing the extension with the `-runnable.jar`, without daccord. Each file names its test call in the header comment. The full test sequence is described in the README of the project (section "Testen" / "Testing").

**Never commit real credentials.** `gp_pwparam1` in `gpconnect.xml` stays a placeholder; copy the files to a directory outside the project and enter the real values there.

## Connection

| File | Action |
|---|---|
| `gpconnect.xml` | connection parameters, `connect` |

## Data collection

| File | Action |
|---|---|
| `gpnodecollection.xml`, `gpnodecollection-group.xml` | `getnodedata` (users, groups) |
| `gpnodepreview.xml`, `gpnodepreview-group.xml` | `getnodepreview` (users, groups) |
| `gprelationcollection.xml` | `getrelationdata` (group memberships) |
| `gprelationpreview.xml` | `getrelationpreview` |

## Provisioning

| File | Action |
|---|---|
| `account-create.xml` | `createaccount` |
| `account-modify.xml` | `modifyaccount` |
| `account-password.xml` | `modifyaccountpassword` |
| `account-status.xml` | `modifyaccountstatus` |
| `account-type.xml` | `modifyaccounttype` |
| `account-rename.xml` | `renameaccount` |
| `account-move.xml` | `moveaccount` |
| `account-reconcile.xml` | `reconcileaccount` |
| `account-delete.xml` | `deleteaccount` |
| `securityobject-create.xml` | `createsecurityobject` |
| `securityobject-modify.xml` | `modifysecurityobject` |
| `securityobject-rename.xml` | `renamesecurityobject` |
| `securityobject-move.xml` | `movesecurityobject` |
| `securityobject-reconcile.xml` | `reconcilesecurityobject` |
| `securityobject-delete.xml` | `deletesecurityobject` |
| `permission-create.xml` | `createpermission` |
| `permission-delete.xml` | `deletepermission` |
| `custom-scimrequest.xml` | `customaccountcommand` / `customsecurityobjectcommand` (needs `gp_param5 = true`) |

Every provisioning file needs a `<data>` element, even an empty one; without it the standalone test aborts with a `NullPointerException`. Replace `YOUR_SCIM_USER_ID` and `YOUR_SCIM_GROUP_ID` with the ids returned by `createaccount` and `createsecurityobject`.

## Running tests

```bash
java -Dlog4j2.level=DEBUG -jar daccord-javaconnector-scim-extension-2.0.0-SNAPSHOT-runnable.jar <action> gpconnect.xml [<file>.xml] [output-directory]
```

Without `-Dlog4j2.level=DEBUG` (or `INFO`) there is no output. `kubernetes/scimple-server.yaml` starts the reference server Apache SCIMple at `http://localhost:30880/v2`.
