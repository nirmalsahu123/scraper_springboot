package com.lugality.scraper.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.client.http.FileContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class GoogleDriveService {

    @Value("${scraper.google-drive-folder-id:}")
    private String folderId;

    @Value("${scraper.google-service-account-json:}")
    private String serviceAccountJson;

    private Drive driveService;
    private boolean enabled = false;

    private static final List<String> SCOPES =
            Collections.singletonList("https://www.googleapis.com/auth/drive.file");

    @PostConstruct
    public void init() {
        if (serviceAccountJson == null || serviceAccountJson.isBlank() ||
            folderId == null || folderId.isBlank()) {
            log.warn("⚠️ Google Drive not configured — uploads disabled");
            return;
        }
        try {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes()))
                    .createScoped(SCOPES);

            driveService = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("lugality-scraper")
                    .build();

            enabled = true;
            log.info("✅ Google Drive initialized. Folder: {}", folderId);
        } catch (Exception e) {
            log.error("❌ Google Drive init failed: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String uploadFile(Path localFile, String mimeType) throws IOException {
        if (!enabled) return null;
        if (!localFile.toFile().exists()) return null;

        String filename = localFile.getFileName().toString();
        String existingId = findFileInFolder(filename);
        File fileMetadata = new File();
        fileMetadata.setName(filename);
        FileContent mediaContent = new FileContent(mimeType, localFile.toFile());

        if (existingId != null) {
            driveService.files().update(existingId, new File(), mediaContent).execute();
            log.info("📝 Updated on Drive: {}", filename);
            return existingId;
        } else {
            fileMetadata.setParents(Collections.singletonList(folderId));
            File uploaded = driveService.files()
                    .create(fileMetadata, mediaContent)
                    .setFields("id, name")
                    .execute();
            log.info("☁️ Uploaded to Drive: {}", filename);
            return uploaded.getId();
        }
    }

    public String uploadJson(Path jsonFile) throws IOException {
        return uploadFile(jsonFile, "application/json");
    }

    public String uploadPdf(Path pdfFile) throws IOException {
        return uploadFile(pdfFile, "application/pdf");
    }

    public String uploadCsv(Path csvFile) throws IOException {
        return uploadFile(csvFile, "text/csv");
    }

    private String findFileInFolder(String filename) {
        try {
            String query = String.format(
                    "name='%s' and '%s' in parents and trashed=false",
                    filename, folderId);
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("files(id, name)")
                    .execute();
            List<File> files = result.getFiles();
            return (files != null && !files.isEmpty()) ? files.get(0).getId() : null;
        } catch (Exception e) {
            log.warn("Could not check existing file {}: {}", filename, e.getMessage());
            return null;
        }
    }
}
