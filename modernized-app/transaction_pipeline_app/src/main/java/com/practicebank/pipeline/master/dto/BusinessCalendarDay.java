package com.practicebank.pipeline.master.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** 01-calendar — GET /business-calendar/{date}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessCalendarDay(LocalDate date, String dayType, String holidayName) {

    /** dayType B = business day. */
    public boolean isBusinessDay() {
        return "B".equals(dayType);
    }
}
