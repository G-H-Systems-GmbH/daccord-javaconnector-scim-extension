package de.guh.extension.connector.scim;

import java.util.Base64;
import java.util.Map;

import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import de.guh.connector.core.connection.ConnectionFactory;

/**
 * Creates SCIMManagedConnection instances.
 *
 * Parameters:
 *   gp_param1  = SCIM Base URL incl. version path (e.g. http://host:8080/v2)
 *   gp_param2  = Auth type: "basic" (default) or "bearer"
 *   gp_param3  = Username (Basic Auth only)
 *   gp_param4  = Page size for collection (default: 100)
 *   gp_pwparam1 = Password (Basic Auth) or Bearer Token
 */
public class SCIMConnectionFactory implements ConnectionFactory<SCIMManagedConnection> {

    @Override
    public SCIMManagedConnection createConnection(Map<String, String> parameters) throws Exception {
        String baseUrl = parameters.get("gp_param1");
        if (baseUrl == null || baseUrl.isBlank())
            throw new Exception("gp_param1 (SCIM Base URL) is missing");
        baseUrl = baseUrl.stripTrailing();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        String authType = parameters.getOrDefault("gp_param2", "basic");
        String username = parameters.getOrDefault("gp_param3", "");
        String secret   = parameters.getOrDefault("gp_pwparam1", "");

        String authHeader;
        if ("bearer".equalsIgnoreCase(authType)) {
            if (secret.isBlank()) throw new Exception("gp_pwparam1 (Bearer Token) is missing");
            authHeader = "Bearer " + secret;
        } else {
            String credentials = username + ":" + secret;
            authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"));
        }

        CloseableHttpClient httpClient = HttpClients.createDefault();

        // Validate connection
        HttpGet get = new HttpGet(baseUrl + "/ServiceProviderConfig");
        get.addHeader("Authorization", authHeader);
        get.addHeader("Accept", "application/scim+json");
        try (CloseableHttpResponse response = httpClient.execute(get)) {
            int status = response.getCode();
            EntityUtils.consume(response.getEntity());
            if (status != 200) {
                httpClient.close();
                throw new Exception("SCIM server returned HTTP " + status + " on ServiceProviderConfig");
            }
        }

        return new SCIMManagedConnection(baseUrl, authHeader, httpClient);
    }

    @Override
    public Class<SCIMManagedConnection> getConnectionType() {
        return SCIMManagedConnection.class;
    }

    @Override
    public String[] getRelevantParameterKeys() {
        return new String[] { "gp_param1", "gp_param2", "gp_param3", "gp_pwparam1" };
    }
}
