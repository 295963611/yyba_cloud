package com.yyba.cloud.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Slf4j
@Component
public class SqliteWriteGuard {

    private static final int MAX_ATTEMPTS = 10;
    private static final long INITIAL_BACKOFF_MS = 25;
    private static final long MAX_BACKOFF_MS = 500;

    private final ReentrantLock lock = new ReentrantLock(true);

    public void write(Runnable action) {
        write(() -> {
            action.run();
            return null;
        });
    }

    public <T> T write(Supplier<T> action) {
        lock.lock();
        try {
            return executeWithRetry(action);
        } finally {
            lock.unlock();
        }
    }

    private <T> T executeWithRetry(Supplier<T> action) {
        long backoff = INITIAL_BACKOFF_MS;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isSqliteBusy(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                log.warn("SQLite busy, retry {}/{}: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        throw last != null ? last : new IllegalStateException("SQLite write failed");
    }

    private boolean isSqliteBusy(Throwable e) {
        while (e != null) {
            if (e instanceof SQLiteException sqlite && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_BUSY) {
                return true;
            }
            if (e instanceof DataAccessException) {
                String message = e.getMessage();
                if (message != null && message.contains("SQLITE_BUSY")) {
                    return true;
                }
            }
            String message = e.getMessage();
            if (message != null && message.contains("SQLITE_BUSY")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SQLite write interrupted", e);
        }
    }
}
