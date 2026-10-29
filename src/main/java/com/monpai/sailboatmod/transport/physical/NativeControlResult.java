package com.monpai.sailboatmod.transport.physical;

/** Failed native control is a reason to pause; it must never be treated as a successful movement command. */
public record NativeControlResult(boolean success, String reason) {
    public static NativeControlResult applied() { return new NativeControlResult(true, ""); }
    public static NativeControlResult failure(String reason) { return new NativeControlResult(false, reason); }
}
