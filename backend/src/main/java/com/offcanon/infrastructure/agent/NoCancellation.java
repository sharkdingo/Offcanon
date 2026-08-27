package com.offcanon.infrastructure.agent;

import com.offcanon.port.CancellationPort;

public final class NoCancellation implements CancellationPort {
    @Override
    public boolean isCancellationRequested() {
        return false;
    }
}
