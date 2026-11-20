package com.project.devsphere.resource.api;

/** ISO weekday 1=Monday; local minutes of day; interval is [startMinute,endMinute). */
public record WeeklySlot(int dayOfWeek, int startMinute, int endMinute) {
    public WeeklySlot {
        if (dayOfWeek<1 || dayOfWeek>7 || startMinute<0 || endMinute>1440 || endMinute<=startMinute) {
            throw new IllegalArgumentException("Invalid weekly working slot");
        }
    }
}
