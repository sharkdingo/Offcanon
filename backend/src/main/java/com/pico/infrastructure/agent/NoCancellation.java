package com.pico.infrastructure.agent;

import com.pico.port.CancellationPort;

public final class NoCancellation implements CancellationPort {
    @Override
    public boolean isCancellationRequested() {
        return false;
    }
}
