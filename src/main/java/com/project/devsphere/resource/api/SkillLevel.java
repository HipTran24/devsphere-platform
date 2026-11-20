package com.project.devsphere.resource.api;

import java.util.Locale;
import java.util.Objects;

/** Public resource contract shared with planning and Work Items. */
public record SkillLevel(String skill, int level) {
    public SkillLevel {
        skill = Objects.requireNonNull(skill).strip().toLowerCase(Locale.ROOT);
        if (skill.isEmpty() || level < 1 || level > 5) throw new IllegalArgumentException("Skill and level 1..5 are required");
    }
}
