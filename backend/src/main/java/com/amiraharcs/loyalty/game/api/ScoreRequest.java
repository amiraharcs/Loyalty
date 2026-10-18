package com.amiraharcs.loyalty.game.api;

import jakarta.validation.constraints.*;
import java.math.BigInteger;

public record ScoreRequest(@NotNull @DecimalMin("0") @DecimalMax("9007199254740991") BigInteger score) { }
