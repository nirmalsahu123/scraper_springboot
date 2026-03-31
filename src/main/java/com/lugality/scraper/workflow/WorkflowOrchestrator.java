package com.lugality.scraper.workflow;

import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.service.*;
import com.lugality.scraper.util.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Component
public class WorkflowOrchestrator {

    private final LoginService loginService;
    private final SearchService searchService;
    private final ExtractionService extractionService;
    
    private final GoogleDriveStorageService localStorageService;
    private final ScraperSettings settings;

    private volatile RateLimiter rateLimiter;

    // ── Re-login every N apps (count-based, not time-based) ──────────────
    // Keeps sessions fresh on Railway where wall-clock time is unpredictable.
    private static final int RELOGIN_EVERY_N_APPS = 50;
    private static final int CHECKPOINT_INTERVAL   = 25;

    private RateLimiter getRateLimiter() {
        if (rateLimiter == null) {
            synchronized (this) {
                if (rateLimiter == null) {
                    rateLimiter = new RateLimiter(settings.getRateLimitPerMinute());
                }
            }
        }
        return rateLimiter;
    }

    /** Step 1: create browser + login. Returns null if login failed after retries. */
    public WorkerContext loginOnly(
            String email, String password,
            List<String> applications, boolean resume, int workerId) {

        ScraperState state = ScraperState.builder()
                .loginEmail(email)
                .loginPassword(password)
                .applicationsQueue(new java.util.ArrayList<>(applications))
                .currentStep(ScraperState.Step.INITIALIZING)
                .maxRetries(settings.getMaxRetries())
                .build();

        if (resume) {
            Set<String> processed = localStorageService.getProcessedApplications();
            state.getApplicationsQueue().removeAll(processed);
            log.info("[Worker {}] Resume mode: {} remaining after skipping {} done",
                    workerId, state.getApplicationsQueue().size(), processed.size());
        }

        BrowserManager browser = new BrowserManager(settings);
        browser.setWorkerId(workerId);
        browser.start();

        for (int attempt = 1; attempt <= 3; attempt++) {
            state = loginService.login(state, browser);
            if (state.isLoggedIn()) {
                log.info("[Worker {}] Login OK (attempt {})", workerId, attempt);
                return new WorkerContext(browser, state);
            }
            if (attempt < 3) {
                log.warn("[Worker {}] Login attempt {} failed — retrying in 15s", workerId, attempt);
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) {}
                state.setErrorMessage(null);
                state.setLoggedIn(false);
                // Recycle browser on 2nd attempt
                if (attempt == 2) {
                    try { browser.stop(); browser.start(); } catch (Exception e) {
                        log.warn("[Worker {}] Browser recycle during login failed: {}", workerId, e.getMessage());
                    }
                }
            }
        }

        browser.stop();
        log.error("[Worker {}] Login failed after 3 attempts — worker will not start", workerId);
        return null;
    }

    public record WorkerContext(BrowserManager browser, ScraperState state) {}

    /** Step 2: scrape all apps. Worker NEVER aborts — it skips single apps on unrecoverable error. */
    public ScraperState scrape(WorkerContext ctx, int workerId) {
        BrowserManager browser = ctx.browser();
        ScraperState state = ctx.state();

        try {
            if (!navigateToSearchForm(state, browser, workerId)) {
                log.error("[Worker {}] Initial navigation failed — recycling and retrying", workerId);
                if (!recycleAndRelogin(browser, state, workerId)) {
                    // Still can't navigate — mark all as failed and exit cleanly
                    markAllRemainingFailed(state, workerId, "Initial navigation failed after recycle");
                    state.setCurrentStep(ScraperState.Step.COMPLETED);
                    return state;
                }
            }

            int appIndex = 0;

            while (!state.getApplicationsQueue().isEmpty()) {

                String appNumber = state.getApplicationsQueue().remove(0);
                state.setCurrentApplication(appNumber);
                state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
                state.setRetryCount(0);
                state.setErrorMessage(null);

                int totalDone = state.getProcessedApplications().size() + state.getFailedApplications().size();
                int remaining = state.getApplicationsQueue().size() + 1;
                log.info("[Worker {}] Processing {} ({}/{})",
                        workerId, appNumber, totalDone + 1, totalDone + remaining);

                try { getRateLimiter().acquire(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Put app back and mark remaining — clean shutdown
                    state.getApplicationsQueue().add(0, appNumber);
                    markAllRemainingFailed(state, workerId, "Worker interrupted");
                    break;
                }

                // ── Count-based re-login (every RELOGIN_EVERY_N_APPS) ────────
                if (appIndex > 0 && appIndex % RELOGIN_EVERY_N_APPS == 0) {
                    log.info("[Worker {}] Scheduled re-login at app #{}", workerId, appIndex);
                    performRelogin(browser, state, workerId);
                    navigateToSearchForm(state, browser, workerId);
                }

                boolean success = false;
                try {
                    success = processSingleApplication(state, browser, appNumber, workerId);
                } catch (Exception e) {
                    // ── Error {} / Playwright context crash ─────────────────────
                    log.error("[Worker {}] Unhandled exception processing {} — recycling browser: {}",
                            workerId, appNumber, e.getMessage());
                    try { browser.screenshot("crash_" + appNumber + "_w" + workerId); } catch (Exception ignored) {}

                    boolean recovered = recycleAndRelogin(browser, state, workerId);
                    if (recovered) {
                        navigateToSearchForm(state, browser, workerId);
                        // Retry the same app once after recovery
                        try {
                            success = processSingleApplication(state, browser, appNumber, workerId);
                        } catch (Exception retryEx) {
                            log.error("[Worker {}] Retry after recycle also failed for {}: {}",
                                    workerId, appNumber, retryEx.getMessage());
                            state.setErrorMessage("Crash + retry failed: " + retryEx.getMessage());
                        }
                    } else {
                        state.setErrorMessage("Browser crash, recycle failed: " + e.getMessage());
                    }
                }

                if (success) {
                    state.getProcessedApplications().add(appNumber);
                    log.info("[Worker {}] ✓ {} done", workerId, appNumber);
                } else {
                    String err = state.getErrorMessage() != null ? state.getErrorMessage() : "Unknown error";
                    state.getFailedApplications().add(java.util.Map.of(
                            "application_number", appNumber,
                            "error", err,
                            "worker_id", workerId
                    ));
                    log.warn("[Worker {}] ✗ {} failed: {}", workerId, appNumber, err);
                    state.setErrorMessage(null);
                }

                appIndex++;

                // ── Delay between apps ───────────────────────────────────────
                if (!state.getApplicationsQueue().isEmpty()) {
                    try {
                        Thread.sleep(settings.getDelayBetweenSearches() * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        markAllRemainingFailed(state, workerId, "Worker interrupted during delay");
                        break;
                    }
                }

                // ── Navigate back to search form ─────────────────────────────
                if (!state.getApplicationsQueue().isEmpty()) {
                    boolean navOk = false;
                    for (int navRetry = 0; navRetry < 3; navRetry++) {
                        if (navigateToSearchForm(state, browser, workerId)) { navOk = true; break; }
                        log.warn("[Worker {}] Nav retry {}/3", workerId, navRetry + 1);
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    }
                    if (!navOk) {
                        log.warn("[Worker {}] Nav failed 3x — recycling browser", workerId);
                        recycleAndRelogin(browser, state, workerId);
                        navigateToSearchForm(state, browser, workerId);
                    }
                }

                // ── Checkpoint ───────────────────────────────────────────────
                if (appIndex % CHECKPOINT_INTERVAL == 0) {
                    try {
                        localStorageService.saveCheckpoint(
                                state.getApplicationsQueue(),
                                state.getProcessedApplications(),
                                state.getFailedApplications(),
                                state.getCheckpointFile());
                    } catch (Exception e) {
                        log.warn("[Worker {}] Checkpoint save failed: {}", workerId, e.getMessage());
                    }
                    log.info("[Worker {}] Checkpoint saved at app #{}", workerId, appIndex);
                }
            }

            state.setCurrentStep(ScraperState.Step.COMPLETED);
            log.info("[Worker {}] Done. Processed: {}, Failed: {}",
                    workerId,
                    state.getProcessedApplications().size(),
                    state.getFailedApplications().size());

        } catch (Exception e) {
            log.error("[Worker {}] Fatal workflow error: {}", workerId, e.getMessage(), e);
            state.setCurrentStep(ScraperState.Step.ERROR);
            state.setErrorMessage(e.getMessage());
            markAllRemainingFailed(state, workerId, "Fatal: " + e.getMessage());
        } finally {
            browser.stop();
        }

        return state;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full browser recycle + re-login. Returns true if login succeeded.
     * NEVER throws — failure is returned as boolean so the caller decides what to do.
     */
    private boolean recycleAndRelogin(BrowserManager browser, ScraperState state, int workerId) {
        log.info("[Worker {}] Recycling browser + re-login...", workerId);
        try {
            browser.stop();
            Thread.sleep(5000);
            browser.start();
            state.setLoggedIn(false);
            state.setErrorMessage(null);
            state = loginService.login(state, browser);
            if (state.isLoggedIn()) {
                log.info("[Worker {}] Re-login after recycle successful", workerId);
                return true;
            }
            log.error("[Worker {}] Re-login after recycle failed", workerId);
            return false;
        } catch (Exception e) {
            log.error("[Worker {}] recycleAndRelogin threw: {}", workerId, e.getMessage());
            return false;
        }
    }

    /**
     * Just re-login without restarting the browser (for scheduled re-logins).
     * On failure, falls back to recycleAndRelogin.
     */
    private void performRelogin(BrowserManager browser, ScraperState state, int workerId) {
        try {
            state.setLoggedIn(false);
            state.setErrorMessage(null);
            state = loginService.login(state, browser);
            if (state.isLoggedIn()) {
                log.info("[Worker {}] Scheduled re-login OK", workerId);
                return;
            }
        } catch (Exception e) {
            log.warn("[Worker {}] Scheduled re-login threw: {}", workerId, e.getMessage());
        }
        log.warn("[Worker {}] Scheduled re-login failed — falling back to browser recycle", workerId);
        recycleAndRelogin(browser, state, workerId);
    }

    private boolean navigateToSearchForm(ScraperState state, BrowserManager browser, int workerId) {
        state.setErrorMessage(null);
        state = searchService.navigateToSearch(state, browser);
        if (state.getCurrentStep() == ScraperState.Step.ERROR) {
            log.error("[Worker {}] navigateToSearch failed: {}", workerId, state.getErrorMessage());
            return false;
        }
        state.setErrorMessage(null);
        state = searchService.selectNationalIrdi(state, browser);
        if (state.getCurrentStep() == ScraperState.Step.ERROR) {
            log.error("[Worker {}] selectNationalIrdi failed: {}", workerId, state.getErrorMessage());
            state.setErrorMessage(null);
            return false;
        }
        return true;
    }

    private boolean processSingleApplication(
            ScraperState state, BrowserManager browser, String appNumber, int workerId) {

        try {
            if (!state.getApplicationsQueue().contains(appNumber)) {
                state.getApplicationsQueue().add(0, appNumber);
            }

            state.setCurrentStep(ScraperState.Step.ENTERING_APPLICATION);
            state = searchService.search(state, browser);
            state.getApplicationsQueue().remove(appNumber);

            if (state.getCurrentStep() == ScraperState.Step.ERROR || state.getErrorMessage() != null) {
                return false;
            }

            state.setCurrentStep(ScraperState.Step.EXTRACTING_DATA);
            state = extractionService.extract(state, browser);
            if (state.getExtractedData() == null) {
                state.setErrorMessage("Extraction returned null for: " + appNumber);
                return false;
            }

            state.setCurrentStep(ScraperState.Step.STORING_DATA);
            try {
                localStorageService.saveApplicationData(appNumber, state.getExtractedData());
            } catch (java.io.IOException e) {
                log.error("[Worker {}] Failed to save data for {}: {}", workerId, appNumber, e.getMessage());
                state.setErrorMessage("Storage error: " + e.getMessage());
                return false;
            }

            return true;

        } catch (Exception e) {
            state.getApplicationsQueue().remove(appNumber);
            // Re-throw so the caller in scrape() can detect Error{} crashes
            throw new RuntimeException("processSingleApplication failed for " + appNumber + ": " + e.getMessage(), e);
        }
    }

    /** Mark all apps still in the queue as failed with a given reason. Never aborts silently. */
    private void markAllRemainingFailed(ScraperState state, int workerId, String reason) {
        if (state.getApplicationsQueue().isEmpty()) return;
        log.warn("[Worker {}] Marking {} remaining apps as failed: {}",
                workerId, state.getApplicationsQueue().size(), reason);
        for (String app : new java.util.ArrayList<>(state.getApplicationsQueue())) {
            state.getFailedApplications().add(java.util.Map.of(
                    "application_number", app,
                    "error", reason,
                    "worker_id", workerId
            ));
        }
        state.getApplicationsQueue().clear();
    }
}
