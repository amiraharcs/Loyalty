package com.amiraharcs.loyalty.qr.service;

/** Internal outcome only. Future scan APIs must map this without exposing cryptographic detail. */
public final class InvalidQrTokenException extends RuntimeException {
    public enum Reason { INVALID_TOKEN, EXPIRED, REVOKED, INACTIVE_CUSTOMER }
    private final Reason reason;
    public InvalidQrTokenException(Reason reason) { super("QR identity token is not valid"); this.reason = reason; }
    public Reason reason() { return reason; }
}
