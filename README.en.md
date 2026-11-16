---
title: JavaConnector Extension SCIM 2.0
---

# daccord JavaConnector Extension – SCIM 2.0

The extension connects daccord via the JavaConnector to any system that offers **SCIM 2.0** (RFC 7643/7644) as a server, e.g. Okta, Keycloak, ServiceNow or Apache SCIMple. It collects users, groups and memberships and writes changes from daccord provisioning back: creating, modifying and deleting users and groups as well as granting and revoking memberships.

Version 2.0 is for **daccord 2.3** (Java 21, Tomcat 11). For daccord 2.2 there is the 1.0 line.

## Contents

- [Features](#features)
- [Installation in daccord](#installation-in-daccord)
- [Connection](#connection)
- [Data collection](#data-collection)
- [Provisioning](#provisioning)
- [Custom commands](#custom-commands)
- [Testing](#testing)
- [Error handling](#error-handling)
- [Technology](#technology)
- [Building](#building)

## Features

- **Data collection:** users (`/Users`) and groups (`/Groups`) as nodes, group memberships as relations; preview with 10 entries
- **Paging** via `startIndex`/`count`, **whitelist/blacklist** and **skipproperties**
- **Provisioning:** all 19 actions for users, groups and memberships
- **Generic attribute mapping:** any SCIM attribute can be set without code changes, including attributes of schema extensions such as the Enterprise User
- **Account names:** strategies `standalone`, `shared` and `unique` (sequential number when the `userName` is taken)
- **Custom command:** free SCIM calls for tests (`scimrequest`, can be disabled)
- **Authentication** via HTTP Basic or bearer token, connection pool

## Installation in daccord

### Prerequisites

- daccord 2.3 with JavaConnector
- A SCIM 2.0 server and an account or token that may read users and groups and – for provisioning – write them
- The base URL of the server **including the version path**, e.g. `https://scim.example.com/scim/v2`

### Files

The extension belongs in the extension directory of the JavaConnector:

```
/opt/GuH/daccord-java-connector/res/extensions/
```

| File | Note |
|---|---|
| `daccord-javaconnector-scim-extension-2.0.x.jar` | the regular JAR, **not** the `-runnable.jar` |

No further JARs are needed: daccord ships Apache HttpClient 5 and `org.json`. Then restart the RuntimeEngine so that the JavaConnector loads the extension (restarting only the gadget is currently not possible).

### Creating the collector

In the daccord frontend create a collector with the JavaConnector and enter as class:

```
de.guh.extension.connector.scim.SCIMJavaConnectorExtension
```

The connection parameters (`gp_param*`) are set on the collector. Which SCIM resource is collected is decided by the **name of the node definition**: `accounts` reads `/Users`, `securityobjects` reads `/Groups`. The relation definition reads the memberships. The values correspond to the example files in `examples/`.

## Connection

| Parameter | Meaning | Example |
|---|---|---|
| `gp_param1` | Base URL including the version path | `https://scim.example.com/scim/v2` |
| `gp_param2` | Authentication: `basic` or `bearer` | `basic` (default) |
| `gp_param3` | User name (`basic` only) | `scimadmin` |
| `gp_param4` | Page size when collecting | `100` (default) |
| `gp_param5` | `true` enables the custom command `scimrequest` (tests only) | empty |
| `gp_pwparam1` | Password (`basic`) or token (`bearer`), encrypted by daccord | |

When connecting, the extension reads `/ServiceProviderConfig`; a status other than 200 aborts the connection. A bearer token is used as it is and not renewed.

Template: `examples/gpconnect.xml`

## Data collection

### Nodes (`np_param*`, preview `cp_param*`)

| Parameter | Meaning | Example |
|---|---|---|
| `np_param1` | Resource, overrides the name of the node definition – only needed for tests without daccord | `accounts`, `securityobjects` |

Collected properties:

| SCIM | daccord – user | | SCIM | daccord – group |
|---|---|---|---|---|
| `id` | `uniqueid` | | `id` | `uniqueid` |
| `userName` | `name` | | `displayName` | `name`, `description` |
| `name.familyName` | `surname` | | `externalId` | `alternativeid` |
| `name.givenName` | `givenname` | | | |
| `displayName` | `alternativename` | | | |
| `externalId` | `alternativeid` | | | |
| `active` | `status` (`active`/`inactive`) | | | |
| `meta.created`, `meta.lastModified` | `created`, `modified` | | | |

Templates: `examples/gpnodecollection.xml` (users), `examples/gpnodecollection-group.xml` (groups), `examples/gpnodepreview*.xml`.

### Relations (`rp_param*`)

The extension reads all groups and their `members`. Each member yields one relation line with `accountid` (SCIM id of the user), `groupid` and `groupname`. No parameters are evaluated. Templates: `examples/gprelationcollection.xml`, `examples/gprelationpreview.xml`.

### Whitelist, blacklist, skipproperties

Nodes and relations can be filtered by rules, e.g. active users only:

```xml
<whitelist>
    <condition>
        <group_nexus>OR</group_nexus>
        <group_internal_nexus>AND</group_internal_nexus>
        <groups>
            <group>
                <rules>
                    <rule>
                        <attribute>status</attribute>
                        <operator>EQUALS</operator>
                        <value>active</value>
                    </rule>
                </rules>
            </group>
        </groups>
    </condition>
</whitelist>
```

## Provisioning

### How the values arrive

The provisioning extension sends two kinds of values:

| Kind | arrives as | Examples |
|---|---|---|
| **IDs and steering values** (named variables) | `pp_config_<name>`, always lower case | `accountuniqueid`, `accountname`, `strategy`, `command` |
| **SCIM attributes** (data XML) | `pp_data_<name>` | `name.givenName`, `emails`, `active`, `PASSWORD` |

SCIM attributes are passed to the server as they are:

- When creating (POST) a dot builds a nested object (`name.givenName`), when modifying (PATCH) the name is used as `path`.
- An attribute of a schema extension is given with its full URN, e.g. `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department`. The URN is added to `schemas` automatically.
- `true`/`false` are written as booleans, a value starting with `[` or `{` as JSON (multi-valued attributes). `emails` given as a plain address becomes the primary work address.

For backward compatibility with provisioning extension 1.0, steering values are also read as `pp_data_`. The attribute names of version 1.0 still work as aliases as long as the SCIM attribute itself is not given:

| Alias | SCIM attribute |
|---|---|
| `accountname` | `userName`, when creating also `displayName` |
| `surname`, `givenname` | `name.familyName`, `name.givenName` |
| `email` | `emails` (primary work address) |
| `status` | `active` (`enabled`, `active`, `true` = active) |
| `name` (group) | `displayName` |

### Actions

| Action | SCIM | Required | Optional (default) |
|---|---|---|---|
| createAccount | `POST /Users` | `accountname` or attribute `userName` | `strategy` (`standalone`), `namelimit` (20), SCIM attributes, `PASSWORD` |
| modifyAccount | `PATCH /Users/{id}`, one `replace` per attribute | `accountuniqueid` | SCIM attributes, aliases |
| deleteAccount | `DELETE /Users/{id}` | `accountuniqueid` | |
| modifyAccountPassword | `PATCH /Users/{id}`, `path password` | `accountuniqueid`, attribute `PASSWORD` | |
| modifyAccountStatus | `PATCH /Users/{id}`, `path active` | `accountuniqueid`, attribute `active` or `status` | |
| modifyAccountType | `PATCH /Users/{id}`, `path userType` | `accountuniqueid`, `accounttype` | |
| renameAccount | `PATCH /Users/{id}`, `path userName` | `accountuniqueid`, `accountname` | |
| moveAccount | like modifyAccount | `accountuniqueid` | SCIM has no containers; attributes such as `department` are changed |
| reconcileAccount | `GET /Users/{id}` | `accountuniqueid` | `reconcileattributes` (all default attributes of the server) |
| customAccountCommand | see [Custom commands](#custom-commands) | `command` | |
| createSecurityObject | `POST /Groups` | `securityobjectname` or attribute `displayName` | SCIM attributes |
| modifySecurityObject, renameSecurityObject, moveSecurityObject | `PATCH /Groups/{id}` | `securityobjectuniqueid` | `securityobjectname` (sets `displayName`), SCIM attributes |
| deleteSecurityObject | `DELETE /Groups/{id}` | `securityobjectuniqueid` | |
| reconcileSecurityObject | `GET /Groups/{id}` | `securityobjectuniqueid` | `reconcileattributes` |
| customSecurityObjectCommand | see [Custom commands](#custom-commands) | `command` | |
| createPermission | `PATCH /Groups/{id}`, `add members` | `accountuniqueid`, `securityobjectuniqueid` | |
| deletePermission | `PATCH /Groups/{id}`, `remove members[value eq "…"]` | `accountuniqueid`, `securityobjectuniqueid` | |

There is an example file in `examples/` for every action. Whether a server accepts `password` or `userType` depends on the server; if it refuses, its error message is returned.

reconcile returns the resource below `<object>`. Attribute names that are not valid in XML (URNs of schema extensions) become `<attribute name="urn:…">`, multi-valued attributes become repeated elements.

### Account names (`strategy`)

| Strategy | Behaviour when the `userName` is taken |
|---|---|
| `standalone` | error from the server (usually `HTTP 409`, `scimType uniqueness`) |
| `shared` | the existing account is used, nothing is created |
| `unique` | a sequential number is appended (`t.mueller1`, `t.mueller2`, …), up to `namelimit` attempts |

The check uses `GET /Users?filter=userName eq "…"`. Besides `<objectuniqueid>` the response of createAccount contains the name actually used in `<accountname>`.

## Custom commands

The action `customcommand` runs a **named command** that does not belong to a change of an object. The command is chosen with `command`:

| Command | Effect | Parameters |
|---|---|---|
| `scimrequest` | sends any call to the SCIM server – **for tests and analysis only** | `httpmethod` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), `resource` (relative to the base URL), optional `body` (JSON) |

`resource` may contain spaces and quotes, e.g. `Users?filter=userName sw "t."`; they are encoded. The response contains `<httpstatus>` and the answer of the server below `<object>`.

`scimrequest` can do anything the connection account is allowed to. It is therefore disabled (`ERROR_SCIMREQUEST_DISABLED`) as long as `gp_param5` is not `true`. Template: `examples/custom-scimrequest.xml`.

## Testing

The test runs the `-runnable.jar` against a **test server** – **not** in the project directory. The JAR contains all dependencies and calls the extension directly, without daccord.

### Test prerequisites

| What | Where from |
|---|---|
| SCIM test server | e.g. Apache SCIMple, see [Reference server](#reference-server-apache-scimple) |
| Password or token | in plain text or encrypted: from the collector of a daccord system (`gp_pwparam1`) together with its `decryptionhash` (`config.xml` of the JavaConnector), **or** encrypted yourself with the test hash `inchorus2012!` (inchorus Security Plugin) |

### Reference server Apache SCIMple

`examples/kubernetes/scimple-server.yaml` starts Apache SCIMple in a local Kubernetes (e.g. Rancher Desktop) in the namespace `scim`:

```bash
kubectl apply -f examples/kubernetes/scimple-server.yaml
kubectl -n scim rollout status deploy/scimple-server
```

The server is then available at `http://localhost:30880/v2` (Basic Auth, test credentials in the manifest). It keeps its data in memory only; a restart resets it. Known deviation: with `?attributes=` SCIMple drops `displayName` and `externalId` of groups – that is why reconcile only filters when `reconcileattributes` is set.

### Preparation

```bash
mvn clean package
mkdir -p ~/scimtest && cp examples/*.xml ~/scimtest/
cp target/daccord-javaconnector-scim-extension-2.0.0-runnable.jar ~/scimtest/
cd ~/scimtest
```

Enter base URL, user and password in `gpconnect.xml`. Replace `YOUR_…` in the request files – take the ids (`YOUR_SCIM_USER_ID`, `YOUR_SCIM_GROUP_ID`) from the output after creating.

### Call

```bash
java -Dlog4j2.level=DEBUG [-Ddecryptionhash=<hash>] -jar daccord-javaconnector-scim-extension-2.0.0-runnable.jar \
  <action> gpconnect.xml [<file>.xml] [output-directory]
```

Without `-Dlog4j2.level=DEBUG` (or `INFO`) there is no output. With `DEBUG` every call to the server is logged with its body; passwords are shortened to their first three characters. For provisioning actions the answer of the extension is in the output: `<success>true</success>` (with `<objectuniqueid>` when creating) or `<success>false</success><error>…</error>`.

### Test sequence

| # | Action | File | Expected | Check on the server |
|---|---|---|---|---|
| 1 | `connect` | – | connection successful | – |
| 2 | `getnodepreview` | `gpnodepreview.xml`, `gpnodepreview-group.xml` | users or groups | – |
| 3 | `getnodedata` | `gpnodecollection.xml`, `gpnodecollection-group.xml`, output directory | CSV with all users or groups | count matches |
| 4 | `createaccount` | `account-create.xml` | `success`, `objectuniqueid` | user with `department` → enter id |
| 5 | `createaccount` | `account-create.xml` once more | `HTTP 409` | – |
| 6 | `createaccount` | with `strategy` `shared` or `unique` | existing id or `<accountname>` with number | – |
| 7 | `modifyaccount` | `account-modify.xml` | `success` | `name.familyName`, `title` |
| 8 | `modifyaccountpassword` | `account-password.xml` | `success` | – |
| 9 | `modifyaccountstatus` | `account-status.xml` | `success` | `active` = `false` |
| 10 | `modifyaccounttype` | `account-type.xml` | `success` | `userType` |
| 11 | `renameaccount` | `account-rename.xml` | `success` | new `userName` |
| 12 | `moveaccount` | `account-move.xml` | `success` | `department` |
| 13 | `reconcileaccount` | `account-reconcile.xml` | `success`, user below `<object>` | – |
| 14 | `createsecurityobject` | `securityobject-create.xml` | `success`, `objectuniqueid` | group exists → enter id |
| 15 | `modifysecurityobject`, `renamesecurityobject`, `movesecurityobject` | `securityobject-modify.xml`, `-rename.xml`, `-move.xml` | `success` | `displayName` |
| 16 | `createpermission` | `permission-create.xml` | `success` | user is member |
| 17 | `getrelationpreview`, `getrelationdata` | `gprelationpreview.xml`, `gprelationcollection.xml` | the new membership | – |
| 18 | `reconcilesecurityobject` | `securityobject-reconcile.xml` | `success`, group below `<object>` | – |
| 19 | `customaccountcommand` | `custom-scimrequest.xml` with `gp_param5` empty | `ERROR_SCIMREQUEST_DISABLED` | – |
| 20 | `customaccountcommand` | `custom-scimrequest.xml` with `gp_param5 = true` | `success`, `httpstatus` 200, answer of the server | – |
| 21 | `deletepermission` | `permission-delete.xml` | `success` | membership removed |
| 22 | `deletesecurityobject` | `securityobject-delete.xml` | `success` | group deleted |
| 23 | `deleteaccount` | `account-delete.xml` | `success` | user deleted |

### Through daccord

A successful test with the `-runnable.jar` does not mean the extension is set up correctly in daccord. Therefore afterwards:

1. Create the collector in daccord, run `connect` and a collection in the frontend.
2. Trigger provisioning through the provisioning extension (request or lifecycle).

## Error handling

Every provisioning action returns `<response><success>false</success><error>…</error></response>` instead of an exception. `error` contains either one of the following texts or `HTTP <status>: <answer of the server>`.

| Error | Cause |
|---|---|
| `Unique id of the object is missing`, `ERROR_MISSING_ACCOUNTUNIQUEID`, `ERROR_MISSING_SECURITYOBJECTUNIQUEID` | id missing |
| `userName (accountname) is missing` | neither `accountname` nor attribute `userName` |
| `displayName (securityobjectname) is missing` | group without a name |
| `PASSWORD is missing`, `accounttype is missing`, `accountname is missing` | required value of the action missing |
| `Nothing to change` | modify without attributes |
| `No free userName found within namelimit` | `unique`: all `namelimit` attempts taken |
| `ERROR_MISSING_COMMAND`, `ERROR_UNKNOWN_COMMAND` | custom command without or with unknown `command` |
| `ERROR_SCIMREQUEST_DISABLED` | `scimrequest` without `gp_param5 = true` |
| `ERROR_INVALID_HTTPMETHOD`, `ERROR_MISSING_RESOURCE` | `scimrequest` incomplete |

Provisioning writes to the log of the JavaConnector (`/opt/GuH/daccord-java-connector/logs/javaconnector.log`), not to the log of the collector.

## Technology

| | |
|---|---|
| Language | Java 21 |
| Build | Maven, Shade plugin for the `-runnable.jar` |
| Platform | daccord 2.3, connector-core 2.3, gadget-core 4.x (Jakarta) |
| SCIM access | Apache HttpClient 5 (from daccord), JSON with `org.json` |
| Logging | Log4j 2 |

## Building

```bash
mvn clean package
```

Result in `target/`: the JAR for daccord and the `-runnable.jar` for tests.
