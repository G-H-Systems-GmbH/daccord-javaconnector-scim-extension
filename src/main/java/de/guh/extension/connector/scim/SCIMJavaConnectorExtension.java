package de.guh.extension.connector.scim;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import de.guh.connector.core.ConnectorRequest;
import de.guh.connector.core.ConnectorResponse;
import de.guh.connector.core.JavaConnectorExtension;
import de.guh.connector.core.NodeCollection;
import de.guh.connector.core.RelationCollection;
import de.guh.connector.core.connection.ConnectionManager;
import de.guh.plugin.rules.RuleEvaluableString;
import de.guh.plugin.xml.XML;

/**
 * daccord JavaConnector Extension for SCIM 2.0 (RFC 7643/7644).
 *
 * Generic SCIM client — works with any SCIM-compliant server
 * (Apache SCIMple, Okta, Azure AD, ServiceNow, etc.).
 *
 * Connection parameters:
 *   gp_param1   = SCIM Base URL incl. version path (e.g. http://host:8080/v2)
 *   gp_param2   = Auth type: "basic" (default) or "bearer"
 *   gp_param3   = Username (Basic Auth only)
 *   gp_param4   = Page size (default: 100)
 *   gp_pwparam1 = Password (Basic Auth) or Bearer Token
 *
 * NodeCollection names (daccord) → SCIM endpoint:
 *   "accounts"        → /Users
 *   "securityobjects" → /Groups
 *   np_param1 overrides the nodename for standalone Main.java testing.
 *
 * RelationCollection: reads /Groups with individual GETs for members.
 *
 * Provisioning (see the parameter contract further down):
 *   createAccount         → POST   /Users (strategy standalone | shared | unique)
 *   modifyAccount         → PATCH  /Users/{id}, one replace operation per attribute
 *   modifyAccountStatus   → PATCH  /Users/{id}, path active
 *   modifyAccountPassword → PATCH  /Users/{id}, path password
 *   modifyAccountType     → PATCH  /Users/{id}, path userType
 *   renameAccount         → PATCH  /Users/{id}, path userName
 *   deleteAccount         → DELETE /Users/{id}
 *   createSecurityObject  → POST   /Groups
 *   modifySecurityObject / renameSecurityObject → PATCH /Groups/{id}
 *   deleteSecurityObject  → DELETE /Groups/{id}
 *   createPermission      → PATCH  /Groups/{id} add member
 *   deletePermission      → PATCH  /Groups/{id} remove member
 */
public class SCIMJavaConnectorExtension implements JavaConnectorExtension {

    // =========================================================================
    // CONNECTION
    // =========================================================================

    @Override
    public XML checkConnection(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("checkConnection()...[running]");
        XML xml = new XML("connection");
        try {
            SCIMManagedConnection conn = getConn(coRequest);
            thislog.info("checkConnection()...[SUCCESS] url=" + conn.getBaseUrl());
            xml.appendNode("resultcode", "0");
            xml.appendNode("connected", "true");
            xml.appendNode("message", "SCIM server reachable: " + conn.getBaseUrl());
        } catch (Exception e) {
            thislog.error("checkConnection()...[ERROR] " + e.getMessage());
            xml.appendNode("resultcode", "ERROR");
            xml.appendNode("connected", "false");
            xml.appendNode("message", e.getMessage());
        }
        return xml;
    }

    // =========================================================================
    // PREVIEW
    // =========================================================================

    @Override
    public XML getNodePreview(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        String nodename = collectionType(coRequest.getParameter("cp_param1"), coRequest.getNodename());
        thislog.info("getNodePreview()...[running] nodename: " + nodename);
        SCIMManagedConnection conn = getConn(coRequest);
        String endpoint = endpointFor(nodename);
        JSONObject page = scimGet(conn, endpoint + "?startIndex=1&count=10");
        XML entries = new XML("entries");
        JSONArray resources = page.optJSONArray("Resources");
        if (resources != null) {
            for (int i = 0; i < resources.length(); i++) {
                XML entry = buildPreviewEntry(nodename, resources.getJSONObject(i));
                if (entry != null) entries.appendXML(entry);
            }
        }
        thislog.info("getNodePreview()...[SUCCESS] " + (resources != null ? resources.length() : 0) + " preview entries");
        return entries;
    }

    @Override
    public XML getRelationPreview(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("getRelationPreview()...[running]");
        SCIMManagedConnection conn = getConn(coRequest);
        XML entries = new XML("entries");
        int count = 0;
        JSONObject page = scimGet(conn, "/Groups?startIndex=1&count=10");
        JSONArray groups = page.optJSONArray("Resources");
        if (groups != null) {
            for (int i = 0; i < groups.length() && count < 10; i++) {
                String groupId = groups.getJSONObject(i).optString("id", "");
                if (groupId.isEmpty()) continue;
                JSONObject group = scimGet(conn, "/Groups/" + groupId);
                JSONArray members = group.optJSONArray("members");
                if (members == null) continue;
                String groupName = group.optString("displayName", groupId);
                for (int j = 0; j < members.length() && count < 10; j++) {
                    String memberId = members.getJSONObject(j).optString("value", "");
                    if (memberId.isEmpty()) continue;
                    XML entry = new XML("entry");
                    entry.appendNode("accountid", memberId);
                    entry.appendNode("groupid", groupId);
                    entry.appendNode("groupname", groupName);
                    entries.appendXML(entry);
                    count++;
                }
            }
        }
        thislog.info("getRelationPreview()...[SUCCESS] " + count + " preview entries");
        return entries;
    }

    // =========================================================================
    // DATA COLLECTION
    // =========================================================================

    @Override
    public boolean getNodeData(Logger thislog, NodeCollection nodecollection, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        String nodename = collectionType(coRequest.getParameter("np_param1"), coRequest.getNodename());
        thislog.info("getNodeData()...[running] nodename: " + nodename);
        SCIMManagedConnection conn = getConn(coRequest);
        boolean hasRules = nodecollection.hasRules();
        int pageSize = pageSize(coRequest);
        String endpoint = endpointFor(nodename);
        int startIndex = 1;
        int totalResults;
        int count = 0;
        int filtered = 0;
        do {
            JSONObject page = scimGet(conn, endpoint + "?startIndex=" + startIndex + "&count=" + pageSize);
            totalResults = page.optInt("totalResults", 0);
            JSONArray resources = page.optJSONArray("Resources");
            if (resources == null || resources.isEmpty()) break;
            for (int i = 0; i < resources.length(); i++) {
                RuleEvaluableString rev = hasRules ? new RuleEvaluableString(coRequest.getNodename()) : null;
                String line = buildNodeLine(nodename, resources.getJSONObject(i), nodecollection, rev, hasRules);
                if (line == null) continue;
                if (!hasRules || nodecollection.isAllowed(thislog, rev)) {
                    coResponse.writeDataLine(line); count++;
                } else filtered++;
            }
            startIndex += pageSize;
        } while (startIndex <= totalResults);
        thislog.info("getNodeData()...[SUCCESS] " + count + " records written, " + filtered + " filtered");
        return true;
    }

    @Override
    public boolean getRelationData(Logger thislog, RelationCollection relationcollection, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("getRelationData()...[running]");
        SCIMManagedConnection conn = getConn(coRequest);
        boolean hasRules = relationcollection.hasRules();
        int pageSize = pageSize(coRequest);
        int startIndex = 1;
        int totalResults;
        int count = 0;
        int filtered = 0;
        do {
            JSONObject page = scimGet(conn, "/Groups?startIndex=" + startIndex + "&count=" + pageSize);
            totalResults = page.optInt("totalResults", 0);
            JSONArray groups = page.optJSONArray("Resources");
            if (groups == null || groups.isEmpty()) break;
            for (int i = 0; i < groups.length(); i++) {
                String groupId = groups.getJSONObject(i).optString("id", "");
                if (groupId.isEmpty()) continue;
                // GET individual group for members
                JSONObject group = scimGet(conn, "/Groups/" + groupId);
                String groupName = group.optString("displayName", groupId);
                JSONArray members = group.optJSONArray("members");
                if (members == null) continue;
                for (int j = 0; j < members.length(); j++) {
                    String memberId = members.getJSONObject(j).optString("value", "");
                    if (memberId.isEmpty()) continue;
                    RuleEvaluableString rev = hasRules ? new RuleEvaluableString(coRequest.getNodename()) : null;
                    if (hasRules) rev.addAttribute("accountid", memberId);
                    String line = "\"accountid:" + escape(memberId) + "\"";
                    line = relationcollection.addDataToLine("groupid", groupId, line, rev);
                    line = relationcollection.addDataToLine("groupname", groupName, line, rev);
                    if (!hasRules || relationcollection.isAllowed(rev)) {
                        coResponse.writeDataLine(line); count++;
                    } else filtered++;
                }
            }
            startIndex += pageSize;
        } while (startIndex <= totalResults);
        thislog.info("getRelationData()...[SUCCESS] " + count + " relations written, " + filtered + " filtered");
        return true;
    }

    // =========================================================================
    // PROVISIONING — PARAMETER CONTRACT
    // =========================================================================
    //
    // The provisioning extension (TaskHandler) sends two kinds of values to the JAVAConnector, which
    // ProvisioningHelper.buildConnectorRequest (daccord-connector-core) turns into request parameters:
    //
    // - SCIM attributes travel in the data XML (//attributes/attribute) and arrive as
    //   pp_data_<attributename>, e.g. pp_data_name.givenName. They are written to the SCIM server as
    //   they are: dots build nested objects (POST) or are used as PATCH path, an attribute of a schema
    //   extension is given with its full URN, e.g.
    //   pp_data_urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department.
    //   PASSWORD is decrypted by the core.
    // - Identifiers and steering values (accountuniqueid, securityobjectuniqueid, accountname,
    //   securityobjectname, accounttype, strategy, namelimit) travel as named GadgetAPI variables and
    //   arrive as pp_config_<name>. Before daccord 2.3 they were sent inside the data XML as well, so
    //   getIdentifier() reads pp_config_ first and falls back to pp_data_.
    //
    // The attribute names of version 1.0 (accountname, surname, givenname, email, status, name) are
    // still understood as aliases. They are only used when the SCIM attribute itself is not given.

    /** Prefix of the SCIM attributes from the data XML, see the parameter contract above. */
    private static final String DATA_PREFIX = "pp_data_";

    /** Prefix of the identifiers and steering values (named variables), see the parameter contract above. */
    private static final String CONFIG_PREFIX = "pp_config_";

    /** Data attributes that are never sent as SCIM attribute: steering values, aliases and the password. */
    private static final Set<String> NON_SCIM_ATTRIBUTES = Set.of(
            "accountuniqueid", "securityobjectuniqueid", "accountname", "securityobjectname", "accounttype",
            "strategy", "namelimit", "surname", "givenname", "email", "status", "name", "PASSWORD", "password");

    private static final String SCHEMA_USER  = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String SCHEMA_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String SCHEMA_PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** Default number of names the "unique" strategy tries before giving up. */
    private static final int DEFAULT_NAMELIMIT = 20;

    // =========================================================================
    // PROVISIONING — ACCOUNT
    // =========================================================================

    /**
     * Creates a user (POST /Users).
     * <p>
     * The body is built from all SCIM attributes of the request. Derived when not given as attribute:
     * userName (accountname), displayName (userName), name.familyName (surname), name.givenName
     * (givenname), emails (email, as primary work address), active (status, default true) and
     * password (PASSWORD).
     * <p>
     * The strategy steers how an already existing userName is handled:
     * <ul>
     * <li>"standalone" (default) - create unconditionally, a duplicate is refused by the server</li>
     * <li>"shared" - reuse the existing user and return its id</li>
     * <li>"unique" - append an increasing counter until a free name is found, at most namelimit
     * attempts (default 20)</li>
     * </ul>
     */
    @Override
    public XML createAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("createAccount()...[running]");
        try {
            SCIMManagedConnection conn = getConn(coRequest);
            Set<String> schemas = new LinkedHashSet<>(List.of(SCHEMA_USER));
            JSONObject user = buildBody(coRequest, schemas);

            String userName = dataAttribute(coRequest, "userName");
            if (userName == null) userName = getIdentifier(coRequest, "accountname");
            if (userName.isBlank()) return buildErrorResponse("userName (accountname) is missing");

            String strategy = getIdentifier(coRequest, "strategy");
            if ("shared".equalsIgnoreCase(strategy)) {
                String existingId = findUserId(conn, userName);
                if (existingId != null) {
                    thislog.info("createAccount()...[SUCCESS] strategy shared, reusing id=" + existingId);
                    return buildAccountResponse(existingId, userName);
                }
            } else if ("unique".equalsIgnoreCase(strategy)) {
                userName = findFreeUserName(conn, userName, nameLimit(coRequest));
                if (userName == null) return buildErrorResponse("No free userName found within namelimit");
            }

            user.put("userName", userName);
            setUnlessProvided(user, coRequest, "displayName", userName);
            setUnlessProvided(user, coRequest, "name.familyName", alias(coRequest, "surname"));
            setUnlessProvided(user, coRequest, "name.givenName", alias(coRequest, "givenname"));
            String email = alias(coRequest, "email");
            if (!email.isEmpty() && dataAttribute(coRequest, "emails") == null)
                user.put("emails", primaryWorkEmail(email));
            String status = alias(coRequest, "status");
            if (dataAttribute(coRequest, "active") == null)
                user.put("active", status.isEmpty() || isActiveStatus(status));
            setUnlessProvided(user, coRequest, "password", password(coRequest));
            user.put("schemas", new JSONArray(schemas));

            ScimResult result = send(thislog, conn, new HttpPost(conn.getBaseUrl() + "/Users"), user);
            if (result.status() == 201) {
                String id = new JSONObject(result.body()).optString("id", "");
                thislog.info("createAccount()...[SUCCESS] id=" + id + " userName=" + userName);
                return buildAccountResponse(id, userName);
            }
            return result.toErrorResponse(thislog, "createAccount()");
        } catch (Exception e) {
            thislog.error("createAccount()...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /**
     * Changes a user (PATCH /Users/{id}): every SCIM attribute of the request becomes a replace
     * operation. The aliases accountname (userName, displayName), surname, givenname, email and status
     * are added when the SCIM attribute itself is not given.
     */
    @Override
    public XML modifyAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("modifyAccount()...[running]");
        String id = getIdentifier(coRequest, "accountuniqueid");
        JSONArray ops = buildReplaceOps(coRequest);
        String accountname = dataValue(coRequest, "accountname");
        addReplaceUnlessProvided(ops, coRequest, "userName", accountname);
        addReplaceUnlessProvided(ops, coRequest, "displayName", accountname);
        addReplaceUnlessProvided(ops, coRequest, "name.familyName", dataValue(coRequest, "surname"));
        addReplaceUnlessProvided(ops, coRequest, "name.givenName", dataValue(coRequest, "givenname"));
        String email = dataValue(coRequest, "email");
        if (!email.isEmpty() && dataAttribute(coRequest, "emails") == null)
            ops.put(replaceOp("emails", primaryWorkEmail(email)));
        String status = dataValue(coRequest, "status");
        if (!status.isEmpty() && dataAttribute(coRequest, "active") == null)
            ops.put(replaceOp("active", isActiveStatus(status)));
        return patch(thislog, coRequest, "modifyAccount()", "/Users/", id, ops);
    }

    @Override
    public XML deleteAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("deleteAccount()...[running]");
        return delete(thislog, coRequest, "deleteAccount()", "/Users/", getIdentifier(coRequest, "accountuniqueid"));
    }

    /** Enables or disables a user (PATCH /Users/{id}, path active) from active, or the alias status. */
    @Override
    public XML modifyAccountStatus(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("modifyAccountStatus()...[running]");
        String active = dataAttribute(coRequest, "active");
        boolean value = active != null ? Boolean.parseBoolean(active) : isActiveStatus(alias(coRequest, "status"));
        JSONArray ops = new JSONArray().put(replaceOp("active", value));
        return patch(thislog, coRequest, "modifyAccountStatus()", "/Users/", getIdentifier(coRequest, "accountuniqueid"), ops);
    }

    /** Sets a new password (PATCH /Users/{id}, path password) from PASSWORD, decrypted by the core. */
    @Override
    public XML modifyAccountPassword(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("modifyAccountPassword()...[running]");
        String password = password(coRequest);
        if (password.isEmpty()) return buildErrorResponse("PASSWORD is missing");
        JSONArray ops = new JSONArray().put(replaceOp("password", password));
        return patch(thislog, coRequest, "modifyAccountPassword()", "/Users/", getIdentifier(coRequest, "accountuniqueid"), ops);
    }

    /** Changes the user type (PATCH /Users/{id}, path userType) from accounttype. */
    @Override
    public XML modifyAccountType(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("modifyAccountType()...[running]");
        String userType = getIdentifier(coRequest, "accounttype");
        if (userType.isEmpty()) return buildErrorResponse("accounttype is missing");
        JSONArray ops = new JSONArray().put(replaceOp("userType", userType));
        return patch(thislog, coRequest, "modifyAccountType()", "/Users/", getIdentifier(coRequest, "accountuniqueid"), ops);
    }

    /** Renames a user (PATCH /Users/{id}, path userName) to accountname. */
    @Override
    public XML renameAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("renameAccount()...[running]");
        String newName = getIdentifier(coRequest, "accountname");
        if (newName.isEmpty()) return buildErrorResponse("accountname is missing");
        JSONArray ops = new JSONArray().put(replaceOp("userName", newName));
        return patch(thislog, coRequest, "renameAccount()", "/Users/", getIdentifier(coRequest, "accountuniqueid"), ops);
    }

    /**
     * "Moves" a user. SCIM has no containers (organizational units), so a move changes organizational
     * attributes such as urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department -
     * the same PATCH /Users/{id} as {@link #modifyAccount}.
     */
    @Override
    public XML moveAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("moveAccount()...[running]");
        return modifyAccount(thislog, coRequest, coResponse);
    }

    /**
     * Reads the current state of a user (GET /Users/{id}). reconcileattributes optionally limits the
     * attributes (SCIM parameter attributes); without it the server returns its default attributes.
     * The filter is not applied by default because servers implement it unreliably (Apache SCIMple
     * drops displayName of groups when attributes is given).
     */
    @Override
    public XML reconcileAccount(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        return reconcile(thislog, coRequest, "reconcileAccount()", "/Users/", "accountuniqueid");
    }

    /** Runs a named command on a user, see {@link #customCommand}. */
    @Override
    public XML customAccountCommand(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        return customCommand(thislog, coRequest, "customAccountCommand()");
    }

    // =========================================================================
    // PROVISIONING — PERMISSION (Group membership)
    // =========================================================================

    @Override
    public XML createPermission(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("createPermission()...[running]");
        String accountId = getIdentifier(coRequest, "accountuniqueid");
        JSONArray ops = new JSONArray().put(new JSONObject()
                .put("op", "add")
                .put("path", "members")
                .put("value", new JSONArray().put(new JSONObject().put("value", accountId))));
        XML response = patch(thislog, coRequest, "createPermission()", "/Groups/", getIdentifier(coRequest, "securityobjectuniqueid"), ops);
        return isSuccess(response) ? buildSuccessResponse("") : response;
    }

    @Override
    public XML deletePermission(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("deletePermission()...[running]");
        String accountId = getIdentifier(coRequest, "accountuniqueid");
        JSONArray ops = new JSONArray().put(new JSONObject()
                .put("op", "remove")
                .put("path", "members[value eq \"" + accountId + "\"]"));
        XML response = patch(thislog, coRequest, "deletePermission()", "/Groups/", getIdentifier(coRequest, "securityobjectuniqueid"), ops);
        return isSuccess(response) ? buildSuccessResponse("") : response;
    }

    // =========================================================================
    // PROVISIONING — SECURITY OBJECT (Group)
    // =========================================================================

    /**
     * Creates a group (POST /Groups) from all SCIM attributes of the request; displayName is derived
     * from securityobjectname (or the alias name) when not given.
     */
    @Override
    public XML createSecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("createSecurityObject()...[running]");
        try {
            SCIMManagedConnection conn = getConn(coRequest);
            Set<String> schemas = new LinkedHashSet<>(List.of(SCHEMA_GROUP));
            JSONObject group = buildBody(coRequest, schemas);
            setUnlessProvided(group, coRequest, "displayName", groupName(coRequest));
            if (group.optString("displayName", "").isBlank())
                return buildErrorResponse("displayName (securityobjectname) is missing");
            group.put("schemas", new JSONArray(schemas));

            ScimResult result = send(thislog, conn, new HttpPost(conn.getBaseUrl() + "/Groups"), group);
            if (result.status() == 201) {
                String id = new JSONObject(result.body()).optString("id", "");
                thislog.info("createSecurityObject()...[SUCCESS] id=" + id);
                return buildSuccessResponse(id);
            }
            return result.toErrorResponse(thislog, "createSecurityObject()");
        } catch (Exception e) {
            thislog.error("createSecurityObject()...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /**
     * Changes a group (PATCH /Groups/{id}): every SCIM attribute of the request becomes a replace
     * operation, displayName is derived from securityobjectname (or the alias name) when not given.
     */
    @Override
    public XML modifySecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("modifySecurityObject()...[running]");
        JSONArray ops = buildReplaceOps(coRequest);
        addReplaceUnlessProvided(ops, coRequest, "displayName", groupName(coRequest));
        return patch(thislog, coRequest, "modifySecurityObject()", "/Groups/", getIdentifier(coRequest, "securityobjectuniqueid"), ops);
    }

    @Override
    public XML deleteSecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("deleteSecurityObject()...[running]");
        return delete(thislog, coRequest, "deleteSecurityObject()", "/Groups/", getIdentifier(coRequest, "securityobjectuniqueid"));
    }

    @Override
    public XML renameSecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("renameSecurityObject()...[running]");
        // SCIM rename = change displayName → same as modifySecurityObject
        return modifySecurityObject(thislog, coRequest, coResponse);
    }

    /** "Moves" a group. SCIM groups have no containers - same PATCH /Groups/{id} as {@link #modifySecurityObject}. */
    @Override
    public XML moveSecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        thislog.info("moveSecurityObject()...[running]");
        return modifySecurityObject(thislog, coRequest, coResponse);
    }

    /** Reads the current state of a group (GET /Groups/{id}), see {@link #reconcileAccount}. */
    @Override
    public XML reconcileSecurityObject(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        return reconcile(thislog, coRequest, "reconcileSecurityObject()", "/Groups/", "securityobjectuniqueid");
    }

    /** Runs a named command on a group, see {@link #customCommand}. */
    @Override
    public XML customSecurityObjectCommand(Logger thislog, ConnectorRequest coRequest, ConnectorResponse coResponse) throws Exception {
        return customCommand(thislog, coRequest, "customSecurityObjectCommand()");
    }

    // =========================================================================
    // PROVISIONING — RECONCILE AND CUSTOM COMMANDS
    // =========================================================================

    /** Command that sends any request to the SCIM server - for tests and analysis only. */
    private static final String COMMAND_SCIMREQUEST = "scimrequest";

    /**
     * Connection parameter that enables {@value #COMMAND_SCIMREQUEST}. Off by default: the command can
     * do anything the connection account is allowed to.
     */
    private static final String PARAM_ALLOW_SCIMREQUEST = "gp_param5";

    private static final Set<String> SCIMREQUEST_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** Reads a resource and returns it below the response, attribute names as described in {@link #jsonToXml}. */
    private XML reconcile(Logger thislog, ConnectorRequest coRequest, String method, String endpoint, String idFieldName) {
        thislog.info(method + "...[running]");
        try {
            String id = getIdentifier(coRequest, idFieldName);
            if (id.isEmpty()) return buildErrorResponse("ERROR_MISSING_" + idFieldName.toUpperCase());
            String attributes = getIdentifier(coRequest, "reconcileattributes");
            SCIMManagedConnection conn = getConn(coRequest);
            String uri = conn.getBaseUrl() + endpoint + id;
            if (!attributes.isEmpty()) uri += "?attributes=" + URLEncoder.encode(attributes, StandardCharsets.UTF_8);
            ScimResult result = send(thislog, conn, new HttpGet(uri), null);
            if (!result.isSuccess()) return result.toErrorResponse(thislog, method);
            XML xml = buildSuccessResponse(id);
            xml.appendXML(jsonToXml("object", new JSONObject(result.body())));
            thislog.info(method + "...[SUCCESS] id=" + id);
            return xml;
        } catch (Exception e) {
            thislog.error(method + "...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /**
     * Runs the command named in the steering value command. Known commands:
     * <ul>
     * <li>{@value #COMMAND_SCIMREQUEST} - sends httpmethod (GET, POST, PUT, PATCH, DELETE) to resource
     * (relative to the base URL, e.g. Users?filter=userName eq "x"), with the JSON body from body.
     * Only if the connection parameter {@value #PARAM_ALLOW_SCIMREQUEST} is "true". Returns the HTTP
     * status as httpstatus and the answer below the response.</li>
     * </ul>
     */
    private XML customCommand(Logger thislog, ConnectorRequest coRequest, String method) {
        thislog.info(method + "...[running]");
        String command = getIdentifier(coRequest, "command");
        if (!COMMAND_SCIMREQUEST.equalsIgnoreCase(command))
            return buildErrorResponse(command.isEmpty() ? "ERROR_MISSING_COMMAND" : "ERROR_UNKNOWN_COMMAND: " + command);
        if (!"true".equalsIgnoreCase(coRequest.getParameter(PARAM_ALLOW_SCIMREQUEST)))
            return buildErrorResponse("ERROR_SCIMREQUEST_DISABLED");
        try {
            String httpMethod = getIdentifier(coRequest, "httpmethod").toUpperCase();
            if (!SCIMREQUEST_METHODS.contains(httpMethod))
                return buildErrorResponse("ERROR_INVALID_HTTPMETHOD: " + httpMethod);
            String resource = getIdentifier(coRequest, "resource");
            if (resource.isEmpty()) return buildErrorResponse("ERROR_MISSING_RESOURCE");
            String bodyText = getIdentifier(coRequest, "body");
            JSONObject body = bodyText.isEmpty() ? null : new JSONObject(bodyText);

            SCIMManagedConnection conn = getConn(coRequest);
            String uri = conn.getBaseUrl() + "/" + encodeResource(resource.startsWith("/") ? resource.substring(1) : resource);
            ScimResult result = send(thislog, conn, new HttpUriRequestBase(httpMethod, URI.create(uri)), body);
            XML xml = new XML("response");
            xml.appendNode("success", String.valueOf(result.isSuccess()));
            xml.appendNode("httpstatus", String.valueOf(result.status()));
            if (!result.isSuccess()) xml.appendNode("error", "HTTP " + result.status());
            String answer = result.body() == null ? "" : result.body().trim();
            if (answer.startsWith("{")) xml.appendXML(jsonToXml("object", new JSONObject(answer)));
            else if (!answer.isEmpty()) xml.appendNode("body", answer);
            thislog.info(method + "...[" + (result.isSuccess() ? "SUCCESS" : "ERROR") + "] " + httpMethod + " " + resource + " HTTP " + result.status());
            return xml;
        } catch (Exception e) {
            thislog.error(method + "...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /** Characters that are kept as they are in a resource; everything else is percent-encoded. */
    private static final String RESOURCE_SAFE_CHARACTERS = "/?=&:;,.-_~!$'()*+@%[]";

    /** Encodes spaces, quotes and other characters a URI does not allow, keeping the URI structure. */
    private static String encodeResource(String resource) {
        StringBuilder encoded = new StringBuilder();
        for (char c : resource.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 0x80 || RESOURCE_SAFE_CHARACTERS.indexOf(c) >= 0)
                encoded.append(c);
            else
                encoded.append(URLEncoder.encode(String.valueOf(c), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    /**
     * Converts JSON into XML. A property becomes an element of the same name; a name that is no valid
     * XML element name (such as the URN of a schema extension) becomes an element "attribute" with
     * the name as attribute. Arrays repeat the element once per entry.
     */
    private static XML jsonToXml(String name, Object value) {
        XML xml = xmlElement(name);
        if (value instanceof JSONObject object) {
            for (String key : new TreeSet<>(object.keySet())) {
                Object child = object.get(key);
                if (child instanceof JSONArray array) {
                    for (int i = 0; i < array.length(); i++) xml.appendXML(jsonToXml(key, array.get(i)));
                } else {
                    xml.appendXML(jsonToXml(key, child));
                }
            }
        } else if (value != null && value != JSONObject.NULL) {
            xml.setText(String.valueOf(value));
        }
        return xml;
    }

    private static final Pattern PATTERN_XML_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private static XML xmlElement(String name) {
        if (PATTERN_XML_NAME.matcher(name).matches()) return new XML(name);
        XML xml = new XML("attribute");
        xml.appendAttribute("name", name);
        return xml;
    }

    // =========================================================================
    // PRIVATE — PROVISIONING HELPERS
    // =========================================================================

    /** Status code and body of a SCIM response. */
    private record ScimResult(int status, String body) {

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        XML toErrorResponse(Logger thislog, String method) {
            thislog.error(method + "...[ERROR] HTTP " + status + " " + body);
            return buildErrorResponse("HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
        }
    }

    /** Sends a request with an optional JSON body; the body is logged with masked passwords. */
    private static ScimResult send(Logger thislog, SCIMManagedConnection conn, HttpUriRequestBase request, JSONObject body) throws Exception {
        request.addHeader("Authorization", conn.getAuthHeader());
        request.addHeader("Accept", "application/scim+json");
        if (body != null) {
            request.addHeader("Content-Type", "application/scim+json");
            request.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));
            thislog.debug(request.getMethod() + " " + request.getRequestUri() + " " + maskJsonSecrets(body));
        }
        try (CloseableHttpResponse response = conn.getHttpClient().execute(request)) {
            String responseBody = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            return new ScimResult(response.getCode(), responseBody);
        }
    }

    /** Sends a PATCH with the given operations to {endpoint}{id} and builds the provisioning response. */
    private XML patch(Logger thislog, ConnectorRequest coRequest, String method, String endpoint, String id, JSONArray ops) {
        try {
            if (id.isEmpty()) return buildErrorResponse("Unique id of the object is missing");
            if (ops.isEmpty()) return buildErrorResponse("Nothing to change");
            SCIMManagedConnection conn = getConn(coRequest);
            JSONObject body = new JSONObject()
                    .put("schemas", new JSONArray().put(SCHEMA_PATCH))
                    .put("Operations", ops);
            ScimResult result = send(thislog, conn, new HttpPatch(conn.getBaseUrl() + endpoint + id), body);
            if (result.isSuccess()) {
                thislog.info(method + "...[SUCCESS] id=" + id);
                return buildSuccessResponse(id);
            }
            return result.toErrorResponse(thislog, method);
        } catch (Exception e) {
            thislog.error(method + "...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /** Sends a DELETE to {endpoint}{id} and builds the provisioning response. */
    private XML delete(Logger thislog, ConnectorRequest coRequest, String method, String endpoint, String id) {
        try {
            if (id.isEmpty()) return buildErrorResponse("Unique id of the object is missing");
            SCIMManagedConnection conn = getConn(coRequest);
            ScimResult result = send(thislog, conn, new HttpDelete(conn.getBaseUrl() + endpoint + id), null);
            if (result.isSuccess()) {
                thislog.info(method + "...[SUCCESS] id=" + id);
                return buildSuccessResponse(id);
            }
            return result.toErrorResponse(thislog, method);
        } catch (Exception e) {
            thislog.error(method + "...[ERROR] " + e.getMessage());
            return buildErrorResponse(e);
        }
    }

    /** Returns the id of the user with the given userName, or null if there is none. */
    private static String findUserId(SCIMManagedConnection conn, String userName) throws Exception {
        String filter = URLEncoder.encode("userName eq \"" + userName.replace("\"", "\\\"") + "\"", StandardCharsets.UTF_8);
        HttpGet get = new HttpGet(conn.getBaseUrl() + "/Users?filter=" + filter);
        get.addHeader("Authorization", conn.getAuthHeader());
        get.addHeader("Accept", "application/scim+json");
        try (CloseableHttpResponse response = conn.getHttpClient().execute(get)) {
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getCode() != 200)
                throw new Exception("SCIM user lookup returned HTTP " + response.getCode() + ": " + body);
            JSONArray resources = new JSONObject(body).optJSONArray("Resources");
            if (resources == null) return null;
            // Not every server filters case-insensitively, so compare the name once more
            for (int i = 0; i < resources.length(); i++) {
                JSONObject resource = resources.getJSONObject(i);
                if (userName.equalsIgnoreCase(resource.optString("userName", "")))
                    return resource.optString("id", null);
            }
            return null;
        }
    }

    /** Finds a userName that is not taken yet: the base name, then base1, base2, ... */
    private static String findFreeUserName(SCIMManagedConnection conn, String base, int limit) throws Exception {
        for (int i = 0; i < limit; i++) {
            String candidate = i == 0 ? base : base + i;
            if (findUserId(conn, candidate) == null) return candidate;
        }
        return null;
    }

    private static int nameLimit(ConnectorRequest coRequest) {
        try {
            return Integer.parseInt(getIdentifier(coRequest, "namelimit"));
        } catch (NumberFormatException e) {
            return DEFAULT_NAMELIMIT;
        }
    }

    /**
     * Reads an identifier or steering value: as named variable ({@value #CONFIG_PREFIX}), otherwise
     * as attribute ({@value #DATA_PREFIX}) as sent before daccord 2.3. Never null.
     */
    private static String getIdentifier(ConnectorRequest coRequest, String name) {
        String value = coRequest.getParameter(CONFIG_PREFIX + name);
        if (value == null || value.isBlank())
            value = coRequest.getParameter(DATA_PREFIX + name);
        return value != null ? value.trim() : "";
    }

    /** Reads an alias as attribute or, for standalone tests, as named variable. Never null. */
    private static String alias(ConnectorRequest coRequest, String name) {
        String value = dataValue(coRequest, name);
        return !value.isEmpty() ? value : getIdentifier(coRequest, name);
    }

    /** Reads a data attribute ({@value #DATA_PREFIX}); empty string if it is missing. */
    private static String dataValue(ConnectorRequest coRequest, String name) {
        String value = coRequest.getParameter(DATA_PREFIX + name);
        return value != null ? value.trim() : "";
    }

    /** Reads a data attribute ({@value #DATA_PREFIX}); null if it is missing or empty. */
    private static String dataAttribute(ConnectorRequest coRequest, String name) {
        String value = coRequest.getParameter(DATA_PREFIX + name);
        return value != null && !value.isBlank() ? value : null;
    }

    private static String password(ConnectorRequest coRequest) {
        String value = dataValue(coRequest, "PASSWORD");
        return !value.isEmpty() ? value : dataValue(coRequest, "password");
    }

    private static String groupName(ConnectorRequest coRequest) {
        String value = getIdentifier(coRequest, "securityobjectname");
        return !value.isEmpty() ? value : dataValue(coRequest, "name");
    }

    /** All SCIM attributes of the request: the data attributes without steering values and aliases. */
    private static Map<String, String> scimAttributes(ConnectorRequest coRequest) {
        Map<String, String> attributes = new TreeMap<>();
        for (Map.Entry<String, String> entry : coRequest.getParameters().entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(DATA_PREFIX) || entry.getValue() == null) continue;
            String path = key.substring(DATA_PREFIX.length());
            if (path.isEmpty() || NON_SCIM_ATTRIBUTES.contains(path)) continue;
            attributes.put(path, entry.getValue());
        }
        return attributes;
    }

    /** Builds a POST body from all SCIM attributes; the URNs of schema extensions are added to schemas. */
    private static JSONObject buildBody(ConnectorRequest coRequest, Set<String> schemas) {
        JSONObject body = new JSONObject();
        scimAttributes(coRequest).forEach((path, value) -> setValue(body, path, toJsonValue(path, value), schemas));
        return body;
    }

    /** Builds one replace operation per SCIM attribute of the request. */
    private static JSONArray buildReplaceOps(ConnectorRequest coRequest) {
        JSONArray ops = new JSONArray();
        scimAttributes(coRequest).forEach((path, value) -> ops.put(replaceOp(path, toJsonValue(path, value))));
        return ops;
    }

    private static JSONObject replaceOp(String path, Object value) {
        return new JSONObject().put("op", "replace").put("path", path).put("value", value);
    }

    /** Adds a replace operation for a derived value unless the attribute is given itself or the value is empty. */
    private static void addReplaceUnlessProvided(JSONArray ops, ConnectorRequest coRequest, String path, String value) {
        if (value != null && !value.isBlank() && dataAttribute(coRequest, path) == null)
            ops.put(replaceOp(path, value));
    }

    /** Sets a derived value unless the attribute is given itself or the value is empty. */
    private static void setUnlessProvided(JSONObject body, ConnectorRequest coRequest, String path, String value) {
        if (value != null && !value.isBlank() && dataAttribute(coRequest, path) == null)
            setValue(body, path, value, new LinkedHashSet<>());
    }

    /**
     * Sets a value at the given attribute path. An attribute of a schema extension (urn:...:name) is
     * placed below its URN, which is added to schemas; dots in the remaining name build nested objects.
     */
    private static void setValue(JSONObject body, String path, Object value, Set<String> schemas) {
        JSONObject target = body;
        String name = path;
        if (path.startsWith("urn:")) {
            int separator = path.lastIndexOf(':');
            String urn = path.substring(0, separator);
            name = path.substring(separator + 1);
            schemas.add(urn);
            target = child(body, urn);
        }
        String[] parts = name.split("\\.");
        for (int i = 0; i < parts.length - 1; i++)
            target = child(target, parts[i]);
        target.put(parts[parts.length - 1], value);
    }

    private static JSONObject child(JSONObject parent, String name) {
        JSONObject child = parent.optJSONObject(name);
        if (child == null) {
            child = new JSONObject();
            parent.put(name, child);
        }
        return child;
    }

    /**
     * Converts an attribute value: "true"/"false" (case-insensitive) become JSON booleans, a value
     * starting with "[" or "{" is taken as JSON (multi-valued or complex attributes), everything else
     * stays a string.
     */
    private static Object toJsonValue(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
            return Boolean.parseBoolean(value);
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("[")) return new JSONArray(trimmed);
            if (trimmed.startsWith("{")) return new JSONObject(trimmed);
        } catch (Exception e) {
            // not valid JSON - keep it as string
        }
        return value;
    }

    /** Converts an attribute value; emails given as plain address becomes the primary work address. */
    private static Object toJsonValue(String path, String value) {
        Object json = toJsonValue(value);
        if ("emails".equals(path) && json instanceof String)
            return primaryWorkEmail(value);
        return json;
    }

    private static boolean isActiveStatus(String status) {
        return "enabled".equalsIgnoreCase(status) || "active".equalsIgnoreCase(status) || "true".equalsIgnoreCase(status);
    }

    private static JSONArray primaryWorkEmail(String email) {
        return new JSONArray().put(new JSONObject().put("type", "work").put("value", email).put("primary", true));
    }

    /** Matches JSON string values whose property name contains "password" (case-insensitive). */
    private static final Pattern PATTERN_JSON_PASSWORD = Pattern.compile("(\"[^\"]*(?i:password)[^\"]*\"\\s*:\\s*\")([^\"]*)(\")");

    /**
     * Returns the body for the log with every password shortened to its first three characters: values
     * of properties whose name contains "password" and values of PATCH operations on such a path.
     */
    private static String maskJsonSecrets(JSONObject body) {
        JSONObject copy = new JSONObject(body.toString());
        JSONArray ops = copy.optJSONArray("Operations");
        if (ops != null) {
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.optJSONObject(i);
                if (op != null && op.optString("path", "").toLowerCase().contains("password") && op.has("value"))
                    op.put("value", shorten(String.valueOf(op.get("value"))));
            }
        }
        Matcher matcher = PATTERN_JSON_PASSWORD.matcher(copy.toString());
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(masked, Matcher.quoteReplacement(matcher.group(1) + shorten(matcher.group(2)) + matcher.group(3)));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    private static String shorten(String value) {
        return value.length() > 3 ? value.substring(0, 3) + "..." : value;
    }

    private static boolean isSuccess(XML response) {
        return "true".equals(response.getValue("success"));
    }

    private static XML buildAccountResponse(String id, String userName) {
        XML xml = buildSuccessResponse(id);
        xml.appendNode("accountname", userName);
        return xml;
    }

    // =========================================================================
    // PRIVATE — CONNECTION
    // =========================================================================

    private SCIMManagedConnection getConn(ConnectorRequest coRequest) throws Exception {
        SCIMManagedConnection conn = ConnectionManager.getInstance()
                .getConnection(coRequest.getParameters(), SCIMManagedConnection.class, new SCIMConnectionFactory());
        if (conn == null) throw new Exception("Could not create SCIM connection");
        return conn;
    }

    // =========================================================================
    // PRIVATE — SCIM HTTP
    // =========================================================================

    private JSONObject scimGet(SCIMManagedConnection conn, String path) throws Exception {
        HttpGet get = new HttpGet(conn.getBaseUrl() + path);
        get.addHeader("Authorization", conn.getAuthHeader());
        get.addHeader("Accept", "application/scim+json");
        try (CloseableHttpResponse response = conn.getHttpClient().execute(get)) {
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int status = response.getCode();
            if (status != 200) throw new Exception("SCIM GET " + path + " returned HTTP " + status + ": " + body);
            return new JSONObject(body);
        }
    }


    // =========================================================================
    // PRIVATE — NODE LINE BUILDING
    // =========================================================================

    private String buildNodeLine(String nodename, JSONObject resource, NodeCollection nc,
            RuleEvaluableString rev, boolean hasRules) {
        String uniqueid = resource.optString("id", "");
        if (uniqueid.isEmpty()) return null;
        if (hasRules) rev.addAttribute("uniqueid", uniqueid);
        String line = "\"uniqueid:" + escape(uniqueid) + "\"";
        if ("accounts".equals(nodename)) {
            line = nc.addDataToLine("name",          resource.optString("userName", ""),                    line, rev);
            line = nc.addDataToLine("surname",        nestedStr(resource, "name", "familyName"),             line, rev);
            line = nc.addDataToLine("givenname",      nestedStr(resource, "name", "givenName"),              line, rev);
            line = nc.addDataToLine("alternativename",resource.optString("displayName", ""),                 line, rev);
            line = nc.addDataToLine("alternativeid",  resource.optString("externalId", ""),                  line, rev);
            line = nc.addDataToLine("status",         resource.optBoolean("active", true) ? "active" : "inactive", line, rev);
            line = nc.addDataToLine("created",        nestedStr(resource, "meta", "created"),                line, rev);
            line = nc.addDataToLine("modified",       nestedStr(resource, "meta", "lastModified"),           line, rev);
        } else {
            // securityobjects / groups
            line = nc.addDataToLine("name",          resource.optString("displayName", uniqueid),            line, rev);
            line = nc.addDataToLine("description",   resource.optString("displayName", ""),                  line, rev);
            line = nc.addDataToLine("alternativeid", resource.optString("externalId", ""),                   line, rev);
        }
        return line;
    }

    private XML buildPreviewEntry(String nodename, JSONObject resource) {
        String id = resource.optString("id", "");
        if (id.isEmpty()) return null;
        XML entry = new XML("entry");
        entry.appendNode("uniqueid", id);
        if ("accounts".equals(nodename)) {
            entry.appendNode("username",  resource.optString("userName", ""));
            entry.appendNode("surname",   nestedStr(resource, "name", "familyName"));
            entry.appendNode("givenname", nestedStr(resource, "name", "givenName"));
            entry.appendNode("status",    resource.optBoolean("active", true) ? "active" : "inactive");
        } else {
            entry.appendNode("name", resource.optString("displayName", id));
        }
        return entry;
    }

    // =========================================================================
    // PRIVATE — UTILITIES
    // =========================================================================

    private String collectionType(String param, String nodename) {
        if (param != null && !param.isBlank()) return param.trim().toLowerCase();
        return nodename.toLowerCase();
    }

    private String endpointFor(String nodename) {
        return "securityobjects".equals(nodename) ? "/Groups" : "/Users";
    }

    private int pageSize(ConnectorRequest coRequest) {
        try { return Integer.parseInt(coRequest.getParameter("gp_param4")); } catch (Exception e) { return 100; }
    }

    private String nestedStr(JSONObject obj, String key1, String key2) {
        JSONObject nested = obj.optJSONObject(key1);
        return nested != null ? nested.optString(key2, "") : "";
    }

    private String param(ConnectorRequest r, String key) {
        String v = r.getParameter(key);
        return v != null ? v : "";
    }


    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static XML buildSuccessResponse(String id) {
        XML xml = new XML("response");
        xml.appendNode("success", "true");
        xml.appendNode("objectuniqueid", id);
        return xml;
    }

    private static XML buildErrorResponse(String msg) {
        XML xml = new XML("response");
        xml.appendNode("success", "false");
        xml.appendNode("error", msg);
        return xml;
    }

    private static XML buildErrorResponse(Exception e) {
        return buildErrorResponse(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

}
