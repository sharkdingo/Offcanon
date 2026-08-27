package com.pico.port;

import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ModelResponse;

public interface ModelPort {
    ModelResponse complete(ModelRequest request);
}
