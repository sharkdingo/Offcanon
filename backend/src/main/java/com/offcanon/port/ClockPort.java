package com.offcanon.port;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
