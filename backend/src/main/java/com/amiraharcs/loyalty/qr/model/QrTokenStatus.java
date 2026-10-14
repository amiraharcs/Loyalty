package com.amiraharcs.loyalty.qr.model;

/** Expiry is computed from server time; confirmation consumes the token while explicit replacement revokes it. */
public enum QrTokenStatus { ACTIVE, REVOKED, CONSUMED }
