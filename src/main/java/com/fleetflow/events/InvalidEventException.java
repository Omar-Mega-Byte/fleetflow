package com.fleetflow.events;

import java.util.Locale;

final class InvalidEventException extends RuntimeException {
    InvalidEventException(String message) { super(message); }

    String code() { return getMessage().toUpperCase(Locale.ROOT).replace(' ', '_'); }
}
