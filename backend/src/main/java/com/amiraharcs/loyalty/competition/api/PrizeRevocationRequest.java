package com.amiraharcs.loyalty.competition.api;
import jakarta.validation.constraints.*;
public record PrizeRevocationRequest(@NotBlank @Size(max=1000) @Pattern(regexp="[^\\x00]*") String reason) {
    public PrizeRevocationRequest {if(reason!=null) {reason=reason.strip();}}
    @Override public String toString() {return "PrizeRevocationRequest[redacted]";}
}
