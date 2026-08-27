package com.offcanon.port;

import com.offcanon.agent.domain.ModelRequest;
import com.offcanon.agent.domain.ModelResponse;

public interface ModelPort {
    ModelResponse complete(ModelRequest request);
}
