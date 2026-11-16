package de.guh.extension.connector.scim;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import de.guh.connector.core.connection.ManagedConnection;

/**
 * Holds an authenticated HTTP connection to a SCIM 2.0 server.
 * Supports Basic Auth and Bearer Token.
 */
public class SCIMManagedConnection implements ManagedConnection {

    private final String baseUrl;
    private final String authHeader;
    private final CloseableHttpClient httpClient;
    private final long createdAt;

    public SCIMManagedConnection(String baseUrl, String authHeader, CloseableHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
        this.httpClient = httpClient;
        this.createdAt = System.currentTimeMillis();
    }

    public String getBaseUrl()    { return baseUrl; }
    public String getAuthHeader() { return authHeader; }
    public CloseableHttpClient getHttpClient() { return httpClient; }

    @Override
    public boolean isValid() {
        try {
            HttpGet get = new HttpGet(baseUrl + "/ServiceProviderConfig");
            get.addHeader("Authorization", authHeader);
            get.addHeader("Accept", "application/scim+json");
            try (CloseableHttpResponse response = httpClient.execute(get)) {
                EntityUtils.consume(response.getEntity());
                return response.getCode() == 200;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        try { httpClient.close(); } catch (Exception ignored) {}
    }

    @Override
    public Object getUnderlyingConnection() {
        return httpClient;
    }

    @Override
    public String toString() {
        long lifetime = System.currentTimeMillis() - createdAt;
        return String.format("SCIMManagedConnection[url=%s, lifetime=%dms]", baseUrl, lifetime);
    }
}
