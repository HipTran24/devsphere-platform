package com.project.devsphere.resource.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.resource.api.AddTimeOffRequest;
import com.project.devsphere.resource.api.CapacityVersionRef;
import com.project.devsphere.resource.api.EffectiveCapacityChangeRequest;
import com.project.devsphere.resource.api.PutResourceCapacityRequest;
import com.project.devsphere.resource.api.ResourceCalendarResponse;
import com.project.devsphere.resource.api.ResourceCapacityResponse;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.resource.infrastructure.persistence.DeveloperCapacityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ResourceCapacityService {

    private final DeveloperCapacityRepository capacityRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public ResourceCapacityService(
            DeveloperCapacityRepository capacityRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.capacityRepository = Objects.requireNonNull(capacityRepository, "capacityRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public record PutCapacityResult(ResourceCapacityResponse response, boolean created) {}

    /**
     * Creates or replaces an effective developer capacity record.
     * Guarded by PM role.
     */
    public PutCapacityResult putCapacity(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            PutResourceCapacityRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw ResourceCapacityException.validationFailed("Request must not be null");
        }

        Long projectVersion = getProjectVersion(projectId);
        if (!Objects.equals(projectVersion, request.expectedProjectVersion())) {
            throw ResourceCapacityException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + projectVersion);
        }

        // Validate developer role
        Boolean isDev = jdbcTemplate.queryForObject(
                "SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, request.developerId());
        if (!Boolean.TRUE.equals(isDev)) {
            throw ResourceCapacityException.assigneeNotDeveloper("Target user is not an active developer on project " + projectId);
        }

        validateWeeklySlots(request.weeklySlots());
        validateTimeOff(request.timeOff());

        if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw ResourceCapacityException.calendarInvalid("effectiveTo must be strictly after effectiveFrom");
        }

        Optional<DeveloperCapacity> existingOpt = capacityRepository.findByProjectIdAndUserIdAndEffectiveFrom(
                projectId, request.developerId(), request.effectiveFrom());

        DeveloperCapacity saved;
        boolean created;

        if (existingOpt.isEmpty()) {
            if (request.expectedCapacityVersion() != null) {
                throw ResourceCapacityException.notFound("Expected capacity version was provided but record does not exist");
            }

            DeveloperCapacity capacity = new DeveloperCapacity();
            capacity.setProjectId(projectId);
            capacity.setUserId(request.developerId());
            capacity.setEffectiveFrom(request.effectiveFrom());
            capacity.setEffectiveTo(request.effectiveTo());
            capacity.setSkills(request.skillLevels());
            capacity.setWeeklySlots(request.weeklySlots());
            capacity.setTimeOff(request.timeOff());
            capacity.setMinutesPerWorkday(request.minutesPerDay());
            capacity.setAllocationPct(request.allocationPct());

            try {
                saved = capacityRepository.save(capacity);
                capacityRepository.flush();
            } catch (DataIntegrityViolationException ex) {
                throw handleDataIntegrityViolation(ex);
            }
            created = true;
        } else {
            DeveloperCapacity existing = existingOpt.get();
            if (request.expectedCapacityVersion() == null) {
                throw ResourceCapacityException.periodOverlap("Capacity record already exists for effectiveFrom: " + request.effectiveFrom());
            }
            if (!Objects.equals(existing.getVersion(), request.expectedCapacityVersion())) {
                throw ResourceCapacityException.planStale("Capacity version is stale. Expected: " + request.expectedCapacityVersion() + ", actual: " + existing.getVersion());
            }

            existing.setEffectiveTo(request.effectiveTo());
            existing.setSkills(request.skillLevels());
            existing.setWeeklySlots(request.weeklySlots());
            existing.setTimeOff(request.timeOff());
            existing.setMinutesPerWorkday(request.minutesPerDay());
            existing.setAllocationPct(request.allocationPct());

            try {
                saved = capacityRepository.save(existing);
                capacityRepository.flush();
            } catch (DataIntegrityViolationException ex) {
                throw handleDataIntegrityViolation(ex);
            }
            created = false;
        }

        Long updatedProjectVersion = getProjectVersion(projectId);
        String eligibility = getEligibility(projectId, request.developerId());
        return new PutCapacityResult(ResourceCapacityResponse.from(saved, eligibility, updatedProjectVersion), created);
    }

    /**
     * Atomically closes a currently open-ended capacity and starts a new one from effectiveFrom.
     * Guarded by PM role.
     */
    public ResourceCapacityResponse effectiveChange(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID developerId,
            EffectiveCapacityChangeRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw ResourceCapacityException.validationFailed("Request must not be null");
        }

        Long projectVersion = getProjectVersion(projectId);
        if (!Objects.equals(projectVersion, request.expectedProjectVersion())) {
            throw ResourceCapacityException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + projectVersion);
        }

        DeveloperCapacity existing = capacityRepository.findByIdAndProjectId(request.supersedesCapacityId(), projectId)
                .orElseThrow(() -> ResourceCapacityException.notFound("Superseded capacity not found with ID: " + request.supersedesCapacityId()));

        if (!Objects.equals(existing.getUserId(), developerId)) {
            throw ResourceCapacityException.validationFailed("Developer ID mismatch for superseded capacity");
        }

        if (!Objects.equals(existing.getVersion(), request.expectedCapacityVersion())) {
            throw ResourceCapacityException.planStale("Capacity version is stale. Expected: " + request.expectedCapacityVersion() + ", actual: " + existing.getVersion());
        }

        if (existing.getEffectiveTo() != null) {
            throw ResourceCapacityException.periodOverlap("Cannot execute effective-change on already closed capacity interval");
        }

        if (!request.effectiveFrom().isAfter(existing.getEffectiveFrom())) {
            throw ResourceCapacityException.calendarInvalid("effectiveFrom must be strictly after the start date of the superseded capacity (" + existing.getEffectiveFrom() + ")");
        }

        validateWeeklySlots(request.weeklySlots());
        validateTimeOff(request.timeOff());

        // Close the old capacity at new effectiveFrom
        existing.setEffectiveTo(request.effectiveFrom());
        capacityRepository.save(existing);
        capacityRepository.flush();

        // Create new open-ended capacity
        DeveloperCapacity newCapacity = new DeveloperCapacity();
        newCapacity.setProjectId(projectId);
        newCapacity.setUserId(developerId);
        newCapacity.setEffectiveFrom(request.effectiveFrom());
        newCapacity.setEffectiveTo(null);
        newCapacity.setSkills(request.skillLevels());
        newCapacity.setWeeklySlots(request.weeklySlots());
        newCapacity.setTimeOff(request.timeOff());
        newCapacity.setMinutesPerWorkday(request.minutesPerDay());
        newCapacity.setAllocationPct(request.allocationPct());

        DeveloperCapacity saved;
        try {
            saved = capacityRepository.save(newCapacity);
            capacityRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw handleDataIntegrityViolation(ex);
        }

        Long updatedProjectVersion = getProjectVersion(projectId);
        String eligibility = getEligibility(projectId, developerId);
        return ResourceCapacityResponse.from(saved, eligibility, updatedProjectVersion);
    }

    /**
     * Appends a time off interval to all capacity records intersecting the requested range.
     * Guarded by PM role.
     */
    public void addTimeOff(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            AddTimeOffRequest request) {

        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw ResourceCapacityException.validationFailed("Request must not be null");
        }

        if (!request.endAt().isAfter(request.startAt())) {
            throw ResourceCapacityException.calendarInvalid("Time off endAt must be strictly after startAt");
        }

        Long projectVersion = getProjectVersion(projectId);
        if (!Objects.equals(projectVersion, request.expectedProjectVersion())) {
            throw ResourceCapacityException.planStale("Project version is stale. Expected: " + request.expectedProjectVersion() + ", actual: " + projectVersion);
        }

        String timezoneStr = getProjectTimezone(projectId);
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate fromDate = request.startAt().atZone(zone).toLocalDate();
        LocalDate toDate = request.endAt().atZone(zone).toLocalDate().plusDays(1);

        List<DeveloperCapacity> intersecting = capacityRepository.findIntersecting(
                projectId, request.developerId(), fromDate, toDate);

        if (intersecting.isEmpty()) {
            throw ResourceCapacityException.capacityMissing("No capacity records cover requested time off interval");
        }

        // Validate expected capacity versions if provided
        if (request.capacityVersions() != null && !request.capacityVersions().isEmpty()) {
            Map<UUID, Long> expectedMap = request.capacityVersions().stream()
                    .collect(Collectors.toMap(CapacityVersionRef::id, CapacityVersionRef::expectedVersion));

            for (DeveloperCapacity cap : intersecting) {
                Long expected = expectedMap.get(cap.getId());
                if (expected == null || !Objects.equals(cap.getVersion(), expected)) {
                    throw ResourceCapacityException.planStale("Capacity version mismatch for record: " + cap.getId());
                }
            }
        }

        TimeOff newTimeOff = new TimeOff(request.startAt(), request.endAt());

        // Check for time off overlap in any intersecting capacity
        for (DeveloperCapacity cap : intersecting) {
            List<TimeOff> existingList = cap.getTimeOff() != null ? cap.getTimeOff() : List.of();
            for (TimeOff existing : existingList) {
                if (newTimeOff.startAt().isBefore(existing.endAt()) && newTimeOff.endAt().isAfter(existing.startAt())) {
                    throw ResourceCapacityException.timeOffOverlap("Time off overlaps with existing interval: " + existing.startAt() + " to " + existing.endAt());
                }
            }
        }

        // Append to each intersecting record
        for (DeveloperCapacity cap : intersecting) {
            List<TimeOff> updated = new ArrayList<>(cap.getTimeOff() != null ? cap.getTimeOff() : List.of());
            updated.add(newTimeOff);
            updated.sort(Comparator.comparing(TimeOff::startAt));
            cap.setTimeOff(updated);
            capacityRepository.save(cap);
        }

        try {
            capacityRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw handleDataIntegrityViolation(ex);
        }
    }

    /**
     * Lists capacity records with optional filters.
     * PM sees all; Developer sees only own records.
     */
    @Transactional(readOnly = true)
    public List<ResourceCapacityResponse> listCapacities(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID developerId,
            LocalDate from,
            LocalDate to) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        boolean isPm = scope.hasRole(ProjectRole.PM);
        if (!isPm) {
            if (developerId == null || !Objects.equals(developerId, principal.userId())) {
                throw ResourceCapacityException.accessDenied("Developers are only authorized to read their own capacity records");
            }
            if (!scope.hasRole(ProjectRole.DEVELOPER)) {
                throw ResourceCapacityException.accessDenied("Only active PMs or DEVELOPERs reading own records are authorized");
            }
        }

        List<DeveloperCapacity> list;
        if (developerId != null) {
            if (from != null || to != null) {
                list = capacityRepository.findIntersecting(projectId, developerId, from, to);
            } else {
                list = capacityRepository.findByProjectIdAndUserIdOrderByEffectiveFromAsc(projectId, developerId);
            }
        } else {
            if (from != null || to != null) {
                list = capacityRepository.findAllIntersecting(projectId, from, to);
            } else {
                list = capacityRepository.findByProjectIdOrderByUserIdAscEffectiveFromAsc(projectId);
            }
        }

        Long projectVersion = getProjectVersion(projectId);
        return list.stream()
                .map(c -> ResourceCapacityResponse.from(c, getEligibility(projectId, c.getUserId()), projectVersion))
                .toList();
    }

    /**
     * Retrieves compiled working calendar for a developer over a date range.
     * PM or own developer authorized.
     */
    @Transactional(readOnly = true)
    public ResourceCalendarResponse getCalendar(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID developerId,
            LocalDate from,
            LocalDate to) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        boolean isPm = scope.hasRole(ProjectRole.PM);
        if (!isPm) {
            if (!Objects.equals(developerId, principal.userId())) {
                throw ResourceCapacityException.accessDenied("Developers are only authorized to read their own calendar");
            }
            if (!scope.hasRole(ProjectRole.DEVELOPER)) {
                throw ResourceCapacityException.accessDenied("Only active PMs or DEVELOPERs reading own calendar are authorized");
            }
        }

        String timezoneStr = getProjectTimezone(projectId);
        ZoneId zone = ZoneId.of(timezoneStr);

        LocalDate startDate = from != null ? from : LocalDate.now(zone);
        LocalDate endDate = to != null ? to : startDate.plusDays(14);

        if (endDate.isBefore(startDate)) {
            throw ResourceCapacityException.calendarInvalid("to date must be at or after from date");
        }

        List<DeveloperCapacity> capacities = capacityRepository.findIntersecting(
                projectId, developerId, startDate, endDate.plusDays(1));

        Long projectVersion = getProjectVersion(projectId);
        String eligibility = getEligibility(projectId, developerId);

        return WorkingCalendarCompiler.compile(
                projectId,
                developerId,
                projectVersion,
                timezoneStr,
                eligibility,
                startDate,
                endDate,
                capacities
        );
    }

    private void validateWeeklySlots(List<WeeklySlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            WeeklySlot a = slots.get(i);
            for (int j = i + 1; j < slots.size(); j++) {
                WeeklySlot b = slots.get(j);
                if (a.dayOfWeek() == b.dayOfWeek()) {
                    if (a.startMinute() < b.endMinute() && a.endMinute() > b.startMinute()) {
                        throw ResourceCapacityException.calendarInvalid("Weekly slots overlap on day " + a.dayOfWeek());
                    }
                }
            }
        }
    }

    private void validateTimeOff(List<TimeOff> timeOffs) {
        if (timeOffs == null || timeOffs.isEmpty()) {
            return;
        }
        for (int i = 0; i < timeOffs.size(); i++) {
            TimeOff a = timeOffs.get(i);
            for (int j = i + 1; j < timeOffs.size(); j++) {
                TimeOff b = timeOffs.get(j);
                if (a.startAt().isBefore(b.endAt()) && a.endAt().isAfter(b.startAt())) {
                    throw ResourceCapacityException.timeOffOverlap("Time off intervals overlap");
                }
            }
        }
    }

    private RuntimeException handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage();
        if (msg != null) {
            if (msg.contains("ex_capacity_effective_overlap") || msg.contains("conflicting key value violates exclusion constraint")) {
                return ResourceCapacityException.periodOverlap("Effective period overlaps with an existing capacity interval");
            }
            if (msg.contains("Capacity requires Developer role")) {
                return ResourceCapacityException.assigneeNotDeveloper("Target user is not an active developer on the project");
            }
            if (msg.contains("Weekly slots overlap")) {
                return ResourceCapacityException.calendarInvalid("Weekly slots overlap");
            }
            if (msg.contains("Time off intervals overlap")) {
                return ResourceCapacityException.timeOffOverlap("Time off intervals overlap");
            }
        }
        return ResourceCapacityException.periodOverlap("Data integrity conflict: " + msg);
    }

    private Long getProjectVersion(UUID projectId) {
        Long v = jdbcTemplate.queryForObject("SELECT version FROM projects WHERE id = ?", Long.class, projectId);
        return v != null ? v : 0L;
    }

    private String getProjectTimezone(UUID projectId) {
        List<String> list = jdbcTemplate.query("SELECT timezone FROM projects WHERE id = ?", (rs, rowNum) -> rs.getString("timezone"), projectId);
        return (list.isEmpty() || list.get(0) == null) ? "UTC" : list.get(0);
    }

    private String getEligibility(UUID projectId, UUID userId) {
        Boolean isDev = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, userId);
        return Boolean.TRUE.equals(isDev) ? "ELIGIBLE" : "INELIGIBLE";
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
            } catch (Exception ignored) {
            }
        }
    }
}
