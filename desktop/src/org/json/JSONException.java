package org.json;

/** Igual que en Android: excepción comprobada. */
public class JSONException extends Exception {
    public JSONException(String s) { super(s); }
    public JSONException(String s, Throwable cause) { super(s, cause); }
    public JSONException(Throwable cause) { super(cause); }
}
