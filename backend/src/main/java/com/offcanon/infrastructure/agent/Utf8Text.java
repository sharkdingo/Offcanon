package com.offcanon.infrastructure.agent;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class Utf8Text {
    private Utf8Text() {
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        for (byte value : bytes) {
            if (value == 0) throw new CharacterCodingException();
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}
