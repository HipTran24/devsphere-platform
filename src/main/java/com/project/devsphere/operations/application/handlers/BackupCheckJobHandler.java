package com.project.devsphere.operations.application.handlers;

import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Nightly backup readiness check.
 *
 * <p>Verifies that the database answers, reports its size and table count, and, when
 * {@code devsphere.backup.directory} is configured, requires the newest backup artefact there to be
 * younger than {@code devsphere.backup.max-age-hours}. A stale or missing backup fails the job so it
 * is visible in the operations API and retried.</p>
 */
@Component
public class BackupCheckJobHandler implements JobHandler {

    public static final int MIN_EXPECTED_TABLES = 36;

    private final JdbcTemplate jdbc;
    private final String backupDirectory;
    private final Duration maxBackupAge;
    private final Clock clock;

    @Autowired
    public BackupCheckJobHandler(
            JdbcTemplate jdbc,
            @Value("${devsphere.backup.directory:}") String backupDirectory,
            @Value("${devsphere.backup.max-age-hours:26}") long maxBackupAgeHours) {
        this(jdbc, backupDirectory, Duration.ofHours(maxBackupAgeHours), Clock.systemUTC());
    }

    BackupCheckJobHandler(JdbcTemplate jdbc, String backupDirectory, Duration maxBackupAge, Clock clock) {
        if (maxBackupAge.isNegative() || maxBackupAge.isZero()) {
            throw new IllegalArgumentException("devsphere.backup.max-age-hours must be positive");
        }
        this.jdbc = jdbc;
        this.backupDirectory = backupDirectory == null ? "" : backupDirectory.strip();
        this.maxBackupAge = maxBackupAge;
        this.clock = clock;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.BACKUP_CHECK;
    }

    @Override
    public JobResult handle(ClaimedJob job) throws IOException {
        Long databaseSize = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'",
                Integer.class);
        if (tables == null || tables < MIN_EXPECTED_TABLES) {
            throw new IllegalStateException("SCHEMA_INCOMPLETE: expected at least " + MIN_EXPECTED_TABLES + " tables");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("databaseSizeBytes", databaseSize);
        details.put("tableCount", tables);
        details.put("backupDirectoryConfigured", !backupDirectory.isEmpty());

        if (!backupDirectory.isEmpty()) {
            Instant newest = newestBackup(Path.of(backupDirectory))
                    .orElseThrow(() -> new IllegalStateException("BACKUP_MISSING"));
            Duration age = Duration.between(newest, clock.instant());
            if (age.compareTo(maxBackupAge) > 0) {
                throw new IllegalStateException("BACKUP_STALE: newest backup is " + age.toHours() + "h old");
            }
            details.put("latestBackupAt", newest.toString());
            details.put("latestBackupAgeSeconds", age.toSeconds());
        }
        details.put("checkedAt", clock.instant().toString());
        details.put("outcome", "HEALTHY");
        return JobResult.of(details);
    }

    static Optional<Instant> newestBackup(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(BackupCheckJobHandler::lastModified)
                    .flatMap(Optional::stream)
                    .max(Instant::compareTo);
        }
    }

    private static Optional<Instant> lastModified(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return Optional.of(time.toInstant());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
