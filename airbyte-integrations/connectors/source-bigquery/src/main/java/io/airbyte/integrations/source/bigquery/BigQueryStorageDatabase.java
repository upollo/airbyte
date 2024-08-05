package io.airbyte.integrations.source.bigquery;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryReadSettings;
import io.airbyte.cdk.db.bigquery.BigQueryDatabase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BigQueryStorageDatabase extends BigQueryDatabase {
    private final String jsonCreds;

    public BigQueryStorageDatabase(String projectId, String jsonCreds) {
        super(projectId, jsonCreds);
        this.jsonCreds = jsonCreds;
    }

    public BigQueryReadClient makeStorageReadClient() throws IOException {
        BigQueryReadSettings.Builder settings = BigQueryReadSettings.newBuilder();
        if (jsonCreds != null && !jsonCreds.isEmpty()) {
            GoogleCredentials creds = GoogleCredentials.fromStream(
                new ByteArrayInputStream(jsonCreds.getBytes(StandardCharsets.UTF_8)));
            settings.setCredentialsProvider(FixedCredentialsProvider.create(creds));
        } else {
            throw new RuntimeException("Missing credentials");
        }
        return BigQueryReadClient.create(settings.build());
    }
}
