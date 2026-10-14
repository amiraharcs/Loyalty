package com.amiraharcs.loyalty.qr.model;

/** Expiry is computed from server time; CONSUMED is reserved for the future confirmation workflow. */
public enum QrTokenStatus { ACTIVE, REVOKED, CONSUMED }
