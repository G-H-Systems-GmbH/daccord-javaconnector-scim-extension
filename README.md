---
title: JavaConnector Extension SCIM 2.0
---

# daccord JavaConnector Extension – SCIM 2.0

Die Extension verbindet daccord über den JavaConnector mit jedem System, das **SCIM 2.0** (RFC 7643/7644) als Server anbietet, z.B. Okta, Keycloak, ServiceNow oder Apache SCIMple. Sie liest Benutzer, Gruppen und Mitgliedschaften ein und schreibt Änderungen aus dem daccord-Provisioning zurück: Benutzer und Gruppen anlegen, ändern, löschen sowie Mitgliedschaften vergeben und entziehen.

Version 2.0 ist für **daccord 2.3** (Java 21, Tomcat 11). Für daccord 2.2 gibt es die Linie 1.0.

## Inhalt

- [Funktionsumfang](#funktionsumfang)
- [Installation in daccord](#installation-in-daccord)
- [Verbindung](#verbindung)
- [Datensammlung](#datensammlung)
- [Provisioning](#provisioning)
- [Custom Commands](#custom-commands)
- [Testen](#testen)
- [Fehlerbehandlung](#fehlerbehandlung)
- [Technik](#technik)
- [Bauen](#bauen)

## Funktionsumfang

- **Datensammlung:** Benutzer (`/Users`) und Gruppen (`/Groups`) als Knoten, Gruppenmitgliedschaften als Relationen; Vorschau mit 10 Einträgen
- **Paging** über `startIndex`/`count`, **Whitelist/Blacklist** und **Skipproperties**
- **Provisioning:** alle 19 Aktionen für Benutzer, Gruppen und Mitgliedschaften
- **Generisches Attribut-Mapping:** jedes SCIM-Attribut lässt sich ohne Codeänderung setzen, auch aus Schema-Erweiterungen wie dem Enterprise User
- **Kontonamen:** Strategien `standalone`, `shared` und `unique` (fortlaufende Nummer bei belegtem `userName`)
- **Custom Command:** freie SCIM-Aufrufe für Tests (`scimrequest`, abschaltbar)
- **Anmeldung** per HTTP Basic oder Bearer Token, Verbindungspool

## Installation in daccord

### Voraussetzungen

- daccord 2.3 mit JavaConnector
- Ein SCIM-2.0-Server und ein Konto bzw. Token, das Benutzer und Gruppen lesen und – für das Provisioning – schreiben darf
- Die Basis-URL des Servers **inklusive Versionspfad**, z.B. `https://scim.example.com/scim/v2`

### Dateien

Die Extension gehört in das Extension-Verzeichnis des JavaConnectors:

```
/opt/GuH/daccord-java-connector/res/extensions/
```

| Datei | Hinweis |
|---|---|
| `daccord-javaconnector-scim-extension-2.0.x.jar` | das normale JAR, **nicht** das `-runnable.jar` |

Weitere JARs sind nicht nötig: Apache HttpClient 5 und `org.json` bringt daccord mit. Danach die RuntimeEngine neu starten, damit der JavaConnector die Extension lädt (ein Neustart nur des Gadgets ist derzeit nicht möglich).

### Collector anlegen

Im daccord-Frontend einen Collector mit dem JavaConnector anlegen und als Klasse eintragen:

```
de.guh.extension.connector.scim.SCIMJavaConnectorExtension
```

Die Verbindungsparameter (`gp_param*`) stehen am Collector. Welche SCIM-Ressource gesammelt wird, entscheidet der **Name der Knotendefinition**: `accounts` liest `/Users`, `securityobjects` liest `/Groups`. Die Relationsdefinition liest die Mitgliedschaften. Die Werte entsprechen den Example-Dateien in `examples/`.

## Verbindung

| Parameter | Bedeutung | Beispiel |
|---|---|---|
| `gp_param1` | Basis-URL inklusive Versionspfad | `https://scim.example.com/scim/v2` |
| `gp_param2` | Anmeldung: `basic` oder `bearer` | `basic` (Standard) |
| `gp_param3` | Benutzername (nur `basic`) | `scimadmin` |
| `gp_param4` | Seitengröße beim Sammeln | `100` (Standard) |
| `gp_param5` | `true` schaltet den Custom Command `scimrequest` frei (nur für Tests) | leer |
| `gp_pwparam1` | Kennwort (`basic`) oder Token (`bearer`), wird von daccord verschlüsselt | |

Beim Verbindungsaufbau ruft die Extension `/ServiceProviderConfig` ab; ein Status ungleich 200 bricht die Verbindung ab. Ein Bearer Token wird unverändert verwendet und nicht erneuert.

Vorlage: `examples/gpconnect.xml`

## Datensammlung

### Knoten (`np_param*`, Vorschau `cp_param*`)

| Parameter | Bedeutung | Beispiel |
|---|---|---|
| `np_param1` | Ressource, überschreibt den Namen der Knotendefinition – nur für Tests ohne daccord nötig | `accounts`, `securityobjects` |

Gesammelte Eigenschaften:

| SCIM | daccord – Benutzer | | SCIM | daccord – Gruppe |
|---|---|---|---|---|
| `id` | `uniqueid` | | `id` | `uniqueid` |
| `userName` | `name` | | `displayName` | `name`, `description` |
| `name.familyName` | `surname` | | `externalId` | `alternativeid` |
| `name.givenName` | `givenname` | | | |
| `displayName` | `alternativename` | | | |
| `externalId` | `alternativeid` | | | |
| `active` | `status` (`active`/`inactive`) | | | |
| `meta.created`, `meta.lastModified` | `created`, `modified` | | | |

Vorlagen: `examples/gpnodecollection.xml` (Benutzer), `examples/gpnodecollection-group.xml` (Gruppen), `examples/gpnodepreview*.xml`.

### Relationen (`rp_param*`)

Die Extension liest alle Gruppen und je Gruppe deren `members`. Je Mitglied entsteht eine Relationszeile mit `accountid` (SCIM-ID des Benutzers), `groupid` und `groupname`. Parameter werden nicht ausgewertet. Vorlagen: `examples/gprelationcollection.xml`, `examples/gprelationpreview.xml`.

### Whitelist, Blacklist, Skipproperties

Knoten und Relationen lassen sich über Regeln filtern, z.B. nur aktive Benutzer:

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

### Wie die Werte ankommen

Die Provisioning Extension schickt zwei Arten von Werten:

| Art | kommt an als | Beispiele |
|---|---|---|
| **IDs und Steuerwerte** (benannte Variablen) | `pp_config_<name>`, immer kleingeschrieben | `accountuniqueid`, `accountname`, `strategy`, `command` |
| **SCIM-Attribute** (Daten-XML) | `pp_data_<name>` | `name.givenName`, `emails`, `active`, `PASSWORD` |

SCIM-Attribute gehen unverändert an den Server:

- Beim Anlegen (POST) erzeugt ein Punkt ein verschachteltes Objekt (`name.givenName`), beim Ändern (PATCH) wird der Name als `path` verwendet.
- Ein Attribut einer Schema-Erweiterung wird mit der vollen URN angegeben, z.B. `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department`. Die URN wird automatisch in `schemas` eingetragen.
- `true`/`false` werden als Boolean geschrieben, ein Wert, der mit `[` oder `{` beginnt, als JSON (mehrwertige Attribute). `emails` als einfache Adresse wird zur primären Arbeitsadresse.

Steuerwerte werden zur Rückwärtskompatibilität mit der Provisioning Extension 1.0 auch als `pp_data_` gelesen. Die Attributnamen der Version 1.0 gelten weiter als Alias, solange das SCIM-Attribut selbst nicht mitkommt:

| Alias | SCIM-Attribut |
|---|---|
| `accountname` | `userName`, beim Anlegen auch `displayName` |
| `surname`, `givenname` | `name.familyName`, `name.givenName` |
| `email` | `emails` (primäre Arbeitsadresse) |
| `status` | `active` (`enabled`, `active`, `true` = aktiv) |
| `name` (Gruppe) | `displayName` |

### Aktionen

| Aktion | SCIM | Pflicht | Optional (Standard) |
|---|---|---|---|
| createAccount | `POST /Users` | `accountname` oder Attribut `userName` | `strategy` (`standalone`), `namelimit` (20), SCIM-Attribute, `PASSWORD` |
| modifyAccount | `PATCH /Users/{id}`, ein `replace` je Attribut | `accountuniqueid` | SCIM-Attribute, Aliase |
| deleteAccount | `DELETE /Users/{id}` | `accountuniqueid` | |
| modifyAccountPassword | `PATCH /Users/{id}`, `path password` | `accountuniqueid`, Attribut `PASSWORD` | |
| modifyAccountStatus | `PATCH /Users/{id}`, `path active` | `accountuniqueid`, Attribut `active` oder `status` | |
| modifyAccountType | `PATCH /Users/{id}`, `path userType` | `accountuniqueid`, `accounttype` | |
| renameAccount | `PATCH /Users/{id}`, `path userName` | `accountuniqueid`, `accountname` | |
| moveAccount | wie modifyAccount | `accountuniqueid` | SCIM kennt keine Container; geändert werden Attribute wie `department` |
| reconcileAccount | `GET /Users/{id}` | `accountuniqueid` | `reconcileattributes` (alle Standardattribute des Servers) |
| customAccountCommand | siehe [Custom Commands](#custom-commands) | `command` | |
| createSecurityObject | `POST /Groups` | `securityobjectname` oder Attribut `displayName` | SCIM-Attribute |
| modifySecurityObject, renameSecurityObject, moveSecurityObject | `PATCH /Groups/{id}` | `securityobjectuniqueid` | `securityobjectname` (setzt `displayName`), SCIM-Attribute |
| deleteSecurityObject | `DELETE /Groups/{id}` | `securityobjectuniqueid` | |
| reconcileSecurityObject | `GET /Groups/{id}` | `securityobjectuniqueid` | `reconcileattributes` |
| customSecurityObjectCommand | siehe [Custom Commands](#custom-commands) | `command` | |
| createPermission | `PATCH /Groups/{id}`, `add members` | `accountuniqueid`, `securityobjectuniqueid` | |
| deletePermission | `PATCH /Groups/{id}`, `remove members[value eq "…"]` | `accountuniqueid`, `securityobjectuniqueid` | |

Zu jeder Aktion gibt es eine Example-Datei in `examples/`. Ob ein Server `password` oder `userType` annimmt, hängt vom Server ab; lehnt er ab, kommt seine Fehlermeldung zurück.

reconcile liefert die Ressource unter `<object>`. Attributnamen, die in XML nicht gültig sind (URNs der Schema-Erweiterungen), werden zu `<attribute name="urn:…">`, mehrwertige Attribute zu wiederholten Elementen.

### Kontonamen (`strategy`)

| Strategie | Verhalten bei belegtem `userName` |
|---|---|
| `standalone` | Fehler des Servers (meist `HTTP 409`, `scimType uniqueness`) |
| `shared` | das vorhandene Konto wird verwendet, nichts angelegt |
| `unique` | eine fortlaufende Nummer wird angehängt (`t.mueller1`, `t.mueller2`, …), bis `namelimit` Versuche |

Geprüft wird per `GET /Users?filter=userName eq "…"`. Die Antwort von createAccount enthält neben `<objectuniqueid>` den tatsächlich vergebenen Namen in `<accountname>`.

## Custom Commands

Die Aktion `customcommand` führt einen **benannten Befehl** aus, der nicht zu einer Änderung eines Objekts gehört. Der Befehl wird mit `command` gewählt:

| Befehl | Wirkung | Parameter |
|---|---|---|
| `scimrequest` | sendet einen beliebigen Aufruf an den SCIM-Server – **nur für Tests und Analyse** | `httpmethod` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), `resource` (relativ zur Basis-URL), optional `body` (JSON) |

`resource` darf Leerzeichen und Anführungszeichen enthalten, z.B. `Users?filter=userName sw "t."`; sie werden kodiert. Die Antwort enthält `<httpstatus>` und die Antwort des Servers unter `<object>`.

`scimrequest` kann alles, was das Konto der Verbindung darf. Er ist deshalb gesperrt (`ERROR_SCIMREQUEST_DISABLED`), solange `gp_param5` nicht `true` ist. Vorlage: `examples/custom-scimrequest.xml`.

## Testen

Der Test läuft mit dem `-runnable.jar` gegen einen **Test-Server** – **nicht** im Projektverzeichnis. Das JAR enthält alle Abhängigkeiten und ruft die Extension direkt auf, ohne daccord.

### Voraussetzungen für den Test

| Was | Woher |
|---|---|
| SCIM-Testserver | z.B. Apache SCIMple, siehe [Referenzserver](#referenzserver-apache-scimple) |
| Kennwort bzw. Token | im Klartext oder verschlüsselt: aus dem Collector eines daccord-Systems (`gp_pwparam1`) zusammen mit dessen `decryptionhash` (`config.xml` des JavaConnectors), **oder** selbst verschlüsselt mit dem Test-Hash `inchorus2012!` (inchorus Security Plugin) |

### Referenzserver Apache SCIMple

`examples/kubernetes/scimple-server.yaml` startet Apache SCIMple in einem lokalen Kubernetes (z.B. Rancher Desktop) im Namespace `scim`:

```bash
kubectl apply -f examples/kubernetes/scimple-server.yaml
kubectl -n scim rollout status deploy/scimple-server
```

Der Server ist dann unter `http://localhost:30880/v2` erreichbar (Basic Auth, Testkennung im Manifest). Er hält die Daten nur im Speicher; nach einem Neustart sind sie zurückgesetzt. Bekannte Abweichung: Mit `?attributes=` verliert SCIMple bei Gruppen `displayName` und `externalId` – deshalb filtert reconcile nur, wenn `reconcileattributes` gesetzt ist.

### Vorbereitung

```bash
mvn clean package
mkdir -p ~/scimtest && cp examples/*.xml ~/scimtest/
cp target/daccord-javaconnector-scim-extension-2.0.0-runnable.jar ~/scimtest/
cd ~/scimtest
```

In `gpconnect.xml` Basis-URL, Benutzer und Kennwort eintragen. In den Auftragsdateien `YOUR_…` ersetzen – die IDs (`YOUR_SCIM_USER_ID`, `YOUR_SCIM_GROUP_ID`) nach dem Anlegen aus der Ausgabe übernehmen.

### Aufruf

```bash
java -Dlog4j2.level=DEBUG [-Ddecryptionhash=<hash>] -jar daccord-javaconnector-scim-extension-2.0.0-runnable.jar \
  <aktion> gpconnect.xml [<datei>.xml] [ausgabeverzeichnis]
```

Ohne `-Dlog4j2.level=DEBUG` (oder `INFO`) gibt es keine Ausgabe. Mit `DEBUG` steht jeder Aufruf an den Server mit seinem Body im Log; Kennwörter sind auf die ersten drei Zeichen gekürzt. Bei Provisioning-Aktionen steht die Antwort der Extension in der Ausgabe: `<success>true</success>` (mit `<objectuniqueid>` beim Anlegen) bzw. `<success>false</success><error>…</error>`.

### Testablauf

| # | Aktion | Datei | Erwartet | Prüfen am Server |
|---|---|---|---|---|
| 1 | `connect` | – | Verbindung erfolgreich | – |
| 2 | `getnodepreview` | `gpnodepreview.xml`, `gpnodepreview-group.xml` | Benutzer bzw. Gruppen | – |
| 3 | `getnodedata` | `gpnodecollection.xml`, `gpnodecollection-group.xml`, Ausgabeverzeichnis | CSV mit allen Benutzern bzw. Gruppen | Anzahl stimmt |
| 4 | `createaccount` | `account-create.xml` | `success`, `objectuniqueid` | Benutzer mit `department` → ID eintragen |
| 5 | `createaccount` | `account-create.xml` noch einmal | `HTTP 409` | – |
| 6 | `createaccount` | mit `strategy` `shared` bzw. `unique` | vorhandene ID bzw. `<accountname>` mit Nummer | – |
| 7 | `modifyaccount` | `account-modify.xml` | `success` | `name.familyName`, `title` |
| 8 | `modifyaccountpassword` | `account-password.xml` | `success` | – |
| 9 | `modifyaccountstatus` | `account-status.xml` | `success` | `active` = `false` |
| 10 | `modifyaccounttype` | `account-type.xml` | `success` | `userType` |
| 11 | `renameaccount` | `account-rename.xml` | `success` | `userName` neu |
| 12 | `moveaccount` | `account-move.xml` | `success` | `department` |
| 13 | `reconcileaccount` | `account-reconcile.xml` | `success`, Benutzer unter `<object>` | – |
| 14 | `createsecurityobject` | `securityobject-create.xml` | `success`, `objectuniqueid` | Gruppe vorhanden → ID eintragen |
| 15 | `modifysecurityobject`, `renamesecurityobject`, `movesecurityobject` | `securityobject-modify.xml`, `-rename.xml`, `-move.xml` | `success` | `displayName` |
| 16 | `createpermission` | `permission-create.xml` | `success` | Benutzer ist Mitglied |
| 17 | `getrelationpreview`, `getrelationdata` | `gprelationpreview.xml`, `gprelationcollection.xml` | die neue Mitgliedschaft | – |
| 18 | `reconcilesecurityobject` | `securityobject-reconcile.xml` | `success`, Gruppe unter `<object>` | – |
| 19 | `customaccountcommand` | `custom-scimrequest.xml` mit `gp_param5` leer | `ERROR_SCIMREQUEST_DISABLED` | – |
| 20 | `customaccountcommand` | `custom-scimrequest.xml` mit `gp_param5 = true` | `success`, `httpstatus` 200, Antwort des Servers | – |
| 21 | `deletepermission` | `permission-delete.xml` | `success` | Mitgliedschaft entfernt |
| 22 | `deletesecurityobject` | `securityobject-delete.xml` | `success` | Gruppe gelöscht |
| 23 | `deleteaccount` | `account-delete.xml` | `success` | Benutzer gelöscht |

### Über daccord

Ein erfolgreicher Test mit dem `-runnable.jar` heißt nicht, dass die Extension in daccord richtig eingebunden ist. Deshalb danach:

1. Collector in daccord anlegen, `connect` und eine Sammlung im Frontend ausführen.
2. Provisioning über die Provisioning Extension auslösen (Antrag bzw. Lebenszyklus).

## Fehlerbehandlung

Jede Provisioning-Aktion liefert `<response><success>false</success><error>…</error></response>` statt einer Exception. `error` enthält entweder einen der folgenden Texte oder `HTTP <status>: <Antwort des Servers>`.

| Fehler | Ursache |
|---|---|
| `Unique id of the object is missing`, `ERROR_MISSING_ACCOUNTUNIQUEID`, `ERROR_MISSING_SECURITYOBJECTUNIQUEID` | ID fehlt |
| `userName (accountname) is missing` | weder `accountname` noch Attribut `userName` |
| `displayName (securityobjectname) is missing` | Gruppe ohne Namen |
| `PASSWORD is missing`, `accounttype is missing`, `accountname is missing` | Pflichtwert der Aktion fehlt |
| `Nothing to change` | modify ohne Attribute |
| `No free userName found within namelimit` | `unique`: alle `namelimit` Versuche belegt |
| `ERROR_MISSING_COMMAND`, `ERROR_UNKNOWN_COMMAND` | Custom Command ohne bzw. mit unbekanntem `command` |
| `ERROR_SCIMREQUEST_DISABLED` | `scimrequest` ohne `gp_param5 = true` |
| `ERROR_INVALID_HTTPMETHOD`, `ERROR_MISSING_RESOURCE` | `scimrequest` unvollständig |

Das Provisioning schreibt in das Log des JavaConnectors (`/opt/GuH/daccord-java-connector/logs/javaconnector.log`), nicht in das Log des Collectors.

## Technik

| | |
|---|---|
| Sprache | Java 21 |
| Build | Maven, Shade-Plugin für das `-runnable.jar` |
| Plattform | daccord 2.3, connector-core 2.3, gadget-core 4.x (Jakarta) |
| SCIM-Zugriff | Apache HttpClient 5 (aus daccord), JSON mit `org.json` |
| Logging | Log4j 2 |

## Bauen

```bash
mvn clean package
```

Ergebnis in `target/`: das JAR für daccord und das `-runnable.jar` für Tests.
