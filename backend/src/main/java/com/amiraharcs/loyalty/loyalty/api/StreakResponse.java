package com.amiraharcs.loyalty.loyalty.api;

import java.time.LocalDate;

public record StreakResponse(int currentStreak, int longestStreak, LocalDate lastQualifyingDate, LocalDate serverDate) { }
