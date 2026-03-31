package com.lugality.scraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps LocalStorageService and auto-uploads to Google Drive after every save.
 * If Google Drive is not configured, falls back to local only.
 */
@Slf4j
@Service
public class GoogleDriveStorageService {

    private final LocalStorageService localStorageService;
    private final GoogleDriveService googleDriveService;

    @Autowired
    public GoogleDriveStorageService(
            LocalStorageService localStorageService,
            GoogleDriveService googleDriveService) {
        this.localStorageService = localStorageService;
        this.googleDriveService = googleDriveService;
    }

    /**
     * Save JSON data locally + upload to Drive
     */
    public Path saveApplicationData(String appNumber, Map<String, Object> data) throws IOException {
        Path savedPath = localStorageService.saveApplicationData(appNumber, data);
        if (googleDriveService.isEnabled()) {
            try {
                googleDriveService.uploadJson(savedPath);
            } catch (Exception e) {
                log.warn("⚠️ Drive upload failed for {}: {}", appNumber, e.getMessage());
            }
        }
        return savedPath;
    }

    /**
     * Save documents metadata locally
     */
    public void saveDocuments(String appNumber, List<Map<String, Object>> documents) throws IOException {
        localStorageService.saveDocuments(appNumber, documents);
    }

    /**
     * Save checkpoint locally + upload to Drive
     */
    public Path saveCheckpoint(
            List<String> queue,
            List<String> processed,
            List<Map<String, Object>> failed,
            String checkpointFile) throws IOException {

        Path savedPath = localStorageService.saveCheckpoint(queue, processed, failed, checkpointFile);
        if (googleDriveService.isEnabled()) {
            try {
                googleDriveService.uploadJson(savedPath);
            } catch (Exception e) {
                log.warn("⚠️ Drive checkpoint upload failed: {}", e.getMessage());
            }
        }
        return savedPath;
    }

    /**
     * Export CSV + upload to Drive
     */
    public Path exportToCsv() throws IOException {
        Path csvPath = localStorageService.exportToCsv();
        if (googleDriveService.isEnabled()) {
            try {
                googleDriveService.uploadCsv(csvPath);
                log.info("✅ CSV uploaded to Google Drive");
            } catch (Exception e) {
                log.warn("⚠️ Drive CSV upload failed: {}", e.getMessage());
            }
        }
        return csvPath;
    }

    // ── Delegate rest to LocalStorageService ──

    public Optional<Map<String, Object>> loadApplicationData(String appNumber) {
        return localStorageService.loadApplicationData(appNumber);
    }

    public Set<String> getProcessedApplications() {
        return localStorageService.getProcessedApplications();
    }

    public Map<String, Object> getProgressSummary() {
        return localStorageService.getProgressSummary();
    }

    public Optional<Map<String, Object>> loadCheckpoint(String checkpointFile) {
        return localStorageService.loadCheckpoint(checkpointFile);
    }

    public void saveProgressEntry(int newProcessed, int newFailed, int totalInput,
                                   double durationSeconds, int workers) throws IOException {
        localStorageService.saveProgressEntry(newProcessed, newFailed, totalInput, durationSeconds, workers);
    }
}
