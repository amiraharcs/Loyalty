package com.amiraharcs.loyalty.competition.service;
import com.amiraharcs.loyalty.competition.model.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public record PrizeAssignmentFilter(UUID customerId,UUID prizeId,CustomerPrizeStatus status,PrizeTriggerType triggerType,Instant awardedFrom,Instant awardedTo) {
    public PrizeAssignmentFilter {
        if(!supported(awardedFrom) || !supported(awardedTo) || (awardedFrom!=null && awardedTo!=null &&
                (!awardedTo.isAfter(awardedFrom) || Duration.between(awardedFrom,awardedTo).compareTo(Duration.ofDays(366))>0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
    private static boolean supported(Instant value) {
        return value==null || (value.getNano()%1000==0 && !value.isBefore(Instant.parse("0001-01-01T00:00:00Z")) && value.isBefore(Instant.parse("+10000-01-01T00:00:00Z")));
    }
    public String scope() {return "assignments:"+Arrays.asList(customerId,prizeId,status,triggerType,awardedFrom,awardedTo);}
}
