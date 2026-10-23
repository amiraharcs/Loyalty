package com.amiraharcs.loyalty.admin.service;

import com.amiraharcs.loyalty.admin.api.OperationalReportDtos.*;
import com.amiraharcs.loyalty.common.api.InvalidRequestException;
import java.time.*;

/** Bounded half-open ranges. Each endpoint chooses civil-date or UTC-instant semantics explicitly. */
public final class OperationalReportRange {
    private OperationalReportRange() { }
    public static InstantRange instants(String from, String to, Instant now) {
        try {
            Instant end=to==null?now:Instant.parse(to);
            Instant start=from==null?end.minus(Duration.ofDays(7)):Instant.parse(from);
            if(!end.isAfter(start) || Duration.between(start,end).compareTo(Duration.ofDays(90))>0
                    || start.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || !end.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {throw invalid();}
            return new InstantRange(start,end);
        } catch(DateTimeException failure) {throw invalid();}
    }
    public static DateRange dates(String from, String to, Instant now) {
        try {
            LocalDate end=to==null?now.atZone(ZoneId.of("Europe/London")).toLocalDate().plusDays(1):LocalDate.parse(to);
            LocalDate start=from==null?end.minusDays(7):LocalDate.parse(from);
            long days=java.time.temporal.ChronoUnit.DAYS.between(start,end);
            if(days<1 || days>90 || start.getYear()<1 || end.getYear()>9999) {throw invalid();}
            return new DateRange(start,end);
        } catch(DateTimeException failure) {throw invalid();}
    }
    private static InvalidRequestException invalid() {return new InvalidRequestException();}
}
