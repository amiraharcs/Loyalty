package com.amiraharcs.loyalty.qr.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record VisitConfirmationRequest(@NotNull UUID scanResolutionId) { }
